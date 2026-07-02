package main

import (
	"context"
	"crypto/tls"
	"flag"
	"fmt"
	"ludicrouslink/backend/pkg/discovery"
	"ludicrouslink/backend/pkg/hub"
	"log"
	"net/http"
	"os"

	"github.com/gorilla/websocket"
)

var (
	httpPort = flag.Int("http", 8080, "HTTP server port")
	tcpPort  = flag.Int("tcp", 8888, "TCP stream listener port")
	tlsCert  = flag.String("cert", "", "Path to TLS certificate file")
	tlsKey   = flag.String("key", "", "Path to TLS private key file")
)

func main() {
	flag.Parse()

	addr := fmt.Sprintf(":%d", *httpPort)

	// 1. Initialize Hub
	h := hub.NewHub()
	go h.Run()

	// 4. Start HTTP Server
	// Serve static files from "public" directory (where frontend build is copied)
	// Get absolute path to public dir

	// In development or production, we expect "public" to be in CWD
	fs := http.FileServer(http.Dir("./public"))
	http.Handle("/", fs)

	http.HandleFunc("/ws", func(w http.ResponseWriter, r *http.Request) {
		serveWs(h, w, r)
	})

	http.HandleFunc("/publish", func(w http.ResponseWriter, r *http.Request) {
		servePublisher(h, w, r)
	})

	// 5. Start mDNS Discovery (advertise TCP streaming port - keep using tcpPort flag for now, even if unused directly)
	// TODO: Update discovery to advertise WebSocket URL?
	// For now, Android manual entry uses HTTP port anyway for initial connection?
	// Actually, Android manual entry expected host:port for TCP.
	// Now it needs host:httpPort for WebSocket.
	// We'll update Android to use httpPort.
	go discovery.StartDiscovery(context.Background(), *httpPort)

	if *tlsCert != "" && *tlsKey != "" {
		// Verify files exist
		if _, err := os.Stat(*tlsCert); os.IsNotExist(err) {
			log.Fatalf("TLS certificate file not found: %s", *tlsCert)
		}
		if _, err := os.Stat(*tlsKey); os.IsNotExist(err) {
			log.Fatalf("TLS key file not found: %s", *tlsKey)
		}

		server := &http.Server{
			Addr: addr,
			TLSConfig: &tls.Config{
				MinVersion: tls.VersionTLS12,
			},
		}

		log.Printf("HTTPS Server started on %s (TLS 1.2+)", addr)
		err := server.ListenAndServeTLS(*tlsCert, *tlsKey)
		if err != nil {
			log.Fatal("ListenAndServeTLS: ", err)
		}
	} else {
		log.Printf("HTTP Server started on %s", addr)
		err := http.ListenAndServe(addr, nil)
		if err != nil {
			log.Fatal("ListenAndServe: ", err)
		}
	}
}

var upgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024 * 1024, // 1MB buffer for video
	CheckOrigin: func(r *http.Request) bool {
		return true // Allow all origins for now (development)
	},
}

func serveWs(h *hub.Hub, w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Println(err)
		return
	}
	client := &hub.Client{
		Hub:  h,
		Conn: conn,
		Send: make(chan []byte, 256),
		OnMessage: func(data []byte) {
			h.HandleViewerMessage(data)
		},
	}
	client.Hub.Register(client)

	// Allow collection of memory referenced by the caller by doing all work in
	// new goroutines.
	go client.WritePump()
	go client.ReadPump()
}

func servePublisher(h *hub.Hub, w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Println(err)
		return
	}
	client := &hub.Client{
		Hub:  h,
		Conn: conn,
		Send: make(chan []byte, 256),
		OnMessage: func(data []byte) {
			h.HandlePublisherMessage(data)
		},
		Unregister: func() {
			// Hub handles unregister via channel logic in Run()
			// But specialized unregister logic can go here if needed
			// For now, standard unregister is fine, but we need to tell Hub it's a PUBLISHER
			// Actually Client.Unregister is called by ReadPump defer.
			// We need a specific UnregisterPublisher channel or method on Hub.
			// Since RegisterPublisher sets h.publisher, we need UnregisterPublisher to clear it.
		},
	}

	// Override Unregister to call UnregisterPublisher
	client.Unregister = func() {
		// We use a channel on Hub
		// But UnregisterPublisher is private on Hub? No, I made it private?
		// I need to add UnregisterPublisher method to Hub (it was added in my previous edit but I should check case)
		// I added `RegisterPublisher` public method.
		// I did NOT add public `UnregisterPublisher` method.
		// I should rely on the fact that `h.unregisterPublisher` channel exists.
		// But it's private.
		// I'll add a public method to `Hub` or just implement it here if I could access the channel.
		// Channel is private.
		// I should have added `UnregisterPublisher` method to `Hub`.
		// Let me add it in a separate edit or assume I can access it via public method I forgot to add?
		// Checking hub.go content again...
		// I added `RegisterPublisher` but not `UnregisterPublisher`.
		// I'll add it to Hub now in `servePublisher` context? No, main cannot access private fields.
		// I'll temporarily omit the custom unregister and add the method to Hub in next step.
		// For now, let's just Register.
	}

	// Wait, ReadPump calls `c.Hub.unregister <- c` by default if `Unregister` is nil.
	// That unregisters it as a CLIENT.
	// But `h.clients` doesn't contain the publisher!
	// `RegisterPublisher` sets `h.publisher`.
	// `unregister` channel deletes from `h.clients`.
	// So standard `ReadPump` cleanup will do NOTHING for publisher (or panic if map lookup fails? No, safe delete).
	// But it won't clear `h.publisher`.
	// So we DO need a custom Unregister.

	// I'll add a public method `UnregisterPublisher` to `hub.go` in a separate step.
	// For now, I'll write the code assuming `h.UnregisterPublisher(client)` exists, and implement it in next step.
	client.Unregister = func() {
		h.UnregisterPublisher(client)
	}

	h.RegisterPublisher(client)

	go client.WritePump()
	go client.ReadPump()
}
