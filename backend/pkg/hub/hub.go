package hub

import (
	"log"
	"sync"
	"time"

	flatbuffers "github.com/google/flatbuffers/go"
	"github.com/gorilla/websocket"
	fb "ludicrouslink/backend/pkg/proto/ludicrouslink"
)

// Client represents a connected WebSocket client
type Client struct {
	Hub  *Hub
	Conn *websocket.Conn
	Send chan []byte

	// Callback for incoming messages
	OnMessage func(data []byte)

	// Custom unregister action (optional)
	Unregister func()
}

// Hub maintains the set of active clients and broadcasts messages to the
// clients.
type Hub struct {
	// Registered clients.
	clients map[*Client]bool

	// The active publisher (Android device)
	publisher *Client

	// Inbound messages from the clients.
	broadcast chan []byte

	// Register requests from the clients.
	register chan *Client

	// Unregister requests from clients.
	unregister chan *Client

	// Register request from the publisher
	registerPublisher chan *Client

	// Unregister request from the publisher
	unregisterPublisher chan *Client

	// Codec info (SPS/PPS) raw bytes to send to new clients
	sps []byte
	pps []byte
	mu  sync.RWMutex
}

func NewHub() *Hub {
	return &Hub{
		broadcast:           make(chan []byte),
		register:            make(chan *Client),
		unregister:          make(chan *Client),
		registerPublisher:   make(chan *Client),
		unregisterPublisher: make(chan *Client),
		clients:             make(map[*Client]bool),
	}
}

func (h *Hub) Run() {
	for {
		select {
		case client := <-h.register:
			h.clients[client] = true
			log.Printf("Viewer connected from %s (%d active)", client.Conn.RemoteAddr(), len(h.clients))
			h.sendCodecInfo(client)
		case client := <-h.unregister:
			if _, ok := h.clients[client]; ok {
				delete(h.clients, client)
				close(client.Send)
				log.Printf("Viewer disconnected from %s (%d active)", client.Conn.RemoteAddr(), len(h.clients))
			}
		case client := <-h.registerPublisher:
			if h.publisher != nil {
				// Disconnect existing publisher
				log.Printf("New publisher connected, disconnecting old one")
				close(h.publisher.Send)
			}
			h.publisher = client
			log.Printf("Publisher connected from %s", client.Conn.RemoteAddr())
		case client := <-h.unregisterPublisher:
			if h.publisher == client {
				h.publisher = nil
				close(client.Send)
				log.Printf("Publisher disconnected from %s", client.Conn.RemoteAddr())
			}
		case message := <-h.broadcast:
			for client := range h.clients {
				select {
				case client.Send <- message:
				default:
					close(client.Send)
					delete(h.clients, client)
				}
			}
		}
	}
}

func (h *Hub) SetCodecInfo(sps, pps []byte) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.sps = sps
	h.pps = pps
}

func (h *Hub) sendCodecInfo(client *Client) {
	h.mu.RLock()
	sps, pps := h.sps, h.pps
	h.mu.RUnlock()

	if len(sps) == 0 || len(pps) == 0 {
		return
	}

	msg := BuildCodecInfoMessage(sps, pps)
	client.Send <- msg
}

// BuildCodecInfoMessage creates a FlatBuffer ServerMessage containing CodecInfo
func BuildCodecInfoMessage(sps, pps []byte) []byte {
	builder := flatbuffers.NewBuilder(256)

	spsOffset := builder.CreateByteVector(sps)
	ppsOffset := builder.CreateByteVector(pps)

	fb.CodecInfoStart(builder)
	fb.CodecInfoAddSps(builder, spsOffset)
	fb.CodecInfoAddPps(builder, ppsOffset)
	codecInfoOffset := fb.CodecInfoEnd(builder)

	fb.ServerMessageStart(builder)
	fb.ServerMessageAddPayloadType(builder, fb.ServerPayloadCodecInfo)
	fb.ServerMessageAddPayload(builder, codecInfoOffset)
	msgOffset := fb.ServerMessageEnd(builder)

	builder.Finish(msgOffset)
	return builder.FinishedBytes()
}

// BuildVideoFrameMessage creates a FlatBuffer ServerMessage containing a VideoFrame
func BuildVideoFrameMessage(data []byte, isKeyFrame bool, timestamp uint64) []byte {
	builder := flatbuffers.NewBuilder(len(data) + 64)

	dataOffset := builder.CreateByteVector(data)

	fb.VideoFrameStart(builder)
	fb.VideoFrameAddData(builder, dataOffset)
	fb.VideoFrameAddKeyFrame(builder, isKeyFrame)
	fb.VideoFrameAddTimestamp(builder, timestamp)
	videoFrameOffset := fb.VideoFrameEnd(builder)

	fb.ServerMessageStart(builder)
	fb.ServerMessageAddPayloadType(builder, fb.ServerPayloadVideoFrame)
	fb.ServerMessageAddPayload(builder, videoFrameOffset)
	msgOffset := fb.ServerMessageEnd(builder)

	builder.Finish(msgOffset)
	return builder.FinishedBytes()
}

// Helpers for reading/writing with heartbeat
const (
	writeWait  = 10 * time.Second
	pongWait   = 60 * time.Second
	pingPeriod = (pongWait * 9) / 10
)

func (c *Client) ReadPump() {
	defer func() {
		if c.Unregister != nil {
			c.Unregister()
		} else {
			c.Hub.unregister <- c
		}
		c.Conn.Close()
	}()
	c.Conn.SetReadLimit(10 * 1024 * 1024) // 10MB limit for video frames
	c.Conn.SetReadDeadline(time.Now().Add(pongWait))
	c.Conn.SetPongHandler(func(string) error { c.Conn.SetReadDeadline(time.Now().Add(pongWait)); return nil })
	for {
		messageType, data, err := c.Conn.ReadMessage()
		if err != nil {
			break
		}

		// Handle binary FlatBuffer messages
		if messageType == websocket.BinaryMessage && len(data) > 0 {
			if c.OnMessage != nil {
				c.OnMessage(data)
			}
		}
	}
}

func (c *Client) WritePump() {
	ticker := time.NewTicker(pingPeriod)
	defer func() {
		ticker.Stop()
		c.Conn.Close()
	}()
	for {
		select {
		case message, ok := <-c.Send:
			c.Conn.SetWriteDeadline(time.Now().Add(writeWait))
			if !ok {
				c.Conn.WriteMessage(websocket.CloseMessage, []byte{})
				return
			}

			// Send as binary WebSocket message
			w, err := c.Conn.NextWriter(websocket.BinaryMessage)
			if err != nil {
				return
			}
			w.Write(message)

			if err := w.Close(); err != nil {
				return
			}
		case <-ticker.C:
			c.Conn.SetWriteDeadline(time.Now().Add(writeWait))
			if err := c.Conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}

func (h *Hub) Broadcast(msg []byte) {
	h.broadcast <- msg
}

func (h *Hub) Register(client *Client) {
	h.register <- client
}

func (h *Hub) RegisterPublisher(client *Client) {
	h.registerPublisher <- client
}

func (h *Hub) UnregisterPublisher(client *Client) {
	h.unregisterPublisher <- client
}

func (h *Hub) ForwardToPublisher(msg []byte) {
	h.mu.RLock()
	pub := h.publisher
	h.mu.RUnlock()

	if pub != nil {
		select {
		case pub.Send <- msg:
		default:
			// Publisher buffer full?
		}
	}
}

// HandleViewerMessage handles messages appearing from a Viewer (Frontend)
// - ClientMessage (e.g. TouchEvent) -> Forward to Publisher
func (h *Hub) HandleViewerMessage(data []byte) {
	// Simple pass-through: Forward opaque bytes to Publisher
	// The Publisher (Android) knows how to decode ClientMessage
	h.ForwardToPublisher(data)
}

// HandlePublisherMessage handles messages appearing from the Publisher (Android)
// - ServerMessage (VideoFrame, CodecInfo) -> Broadcast to Viewers
// - Also inspects CodecInfo to cache SPS/PPS
func (h *Hub) HandlePublisherMessage(data []byte) {
	// 1. Broadcast to all viewers
	h.Broadcast(data)

	// 2. Inspect to see if it's CodecInfo (needs caching)
	// We only decode enough to check the type
	msg := fb.GetRootAsServerMessage(data, 0)
	if msg.PayloadType() == fb.ServerPayloadCodecInfo {
		unionTable := new(flatbuffers.Table)
		if msg.Payload(unionTable) {
			codecInfo := new(fb.CodecInfo)
			codecInfo.Init(unionTable.Bytes, unionTable.Pos)
			h.SetCodecInfo(codecInfo.SpsBytes(), codecInfo.PpsBytes())
		}
	}
}

