package mpegts

import (
	"bytes"
	"encoding/binary"
	"io"
	"log"
)

// FrameHandler is a callback for received frames
type FrameHandler func(data []byte, isKeyFrame bool)

// Parser handles length-prefixed H.264 stream parsing
type Parser struct {
	Sps          []byte
	Pps          []byte
	OnVideoFrame FrameHandler
}

func NewParser() *Parser {
	return &Parser{}
}

// HandleStream reads length-prefixed frames from the Reader
func (p *Parser) HandleStream(r io.Reader) {
	lenBuf := make([]byte, 4)
	for {
		// Read 4-byte length
		if _, err := io.ReadFull(r, lenBuf); err != nil {
			if err == io.EOF {
				log.Println("Stream ended")
			} else {
				log.Printf("Read length error: %v", err)
			}
			return
		}

		length := binary.BigEndian.Uint32(lenBuf)
		if length == 0 {
			continue
		}
		
		// Sanity check for length (prevent OOM on bad data)
		if length > 10*1024*1024 { // 10MB max frame
			log.Printf("Frame invalid length: %d", length)
			return
		}

		// Read frame data
		data := make([]byte, length)
		if _, err := io.ReadFull(r, data); err != nil {
			log.Printf("Read data error: %v", err)
			return
		}

		// Scan for NAL units to extract SPS/PPS and detect keyframes
		isKeyFrame := false
		p.scanNALs(data, &isKeyFrame)

		if p.OnVideoFrame != nil {
			p.OnVideoFrame(data, isKeyFrame)
		}
	}
}

// scanNALs scans H.264 byte stream data for NAL units to:
// - Extract SPS (NAL type 7) and PPS (NAL type 8) for codec initialization
// - Detect IDR (keyframe) NAL units (NAL type 5)
func (p *Parser) scanNALs(data []byte, isKeyFrame *bool) {
	n := len(data)
	i := 0

	for i < n-4 {
		// Look for start code: 00 00 01 or 00 00 00 01
		if data[i] == 0 && data[i+1] == 0 {
			startCodeLen := 0
			if data[i+2] == 1 {
				startCodeLen = 3
			} else if data[i+2] == 0 && i+3 < n && data[i+3] == 1 {
				startCodeLen = 4
			}

			if startCodeLen > 0 {
				nalStart := i + startCodeLen
				if nalStart < n {
					nalType := data[nalStart] & 0x1F

					switch nalType {
					case 5: // IDR slice
						*isKeyFrame = true
					case 7: // SPS
						end := findNALEnd(data, nalStart)
						if len(p.Sps) == 0 || !bytes.Equal(p.Sps, data[nalStart:end]) {
							p.Sps = make([]byte, end-nalStart)
							copy(p.Sps, data[nalStart:end])
							log.Printf("Extracted SPS: %d bytes", len(p.Sps))
						}
					case 8: // PPS
						end := findNALEnd(data, nalStart)
						if len(p.Pps) == 0 || !bytes.Equal(p.Pps, data[nalStart:end]) {
							p.Pps = make([]byte, end-nalStart)
							copy(p.Pps, data[nalStart:end])
							log.Printf("Extracted PPS: %d bytes", len(p.Pps))
						}
					}
				}
				i = nalStart
				continue
			}
		}
		i++
	}
}

// findNALEnd finds the end of a NAL unit (start of next start code or end of data)
func findNALEnd(data []byte, start int) int {
	for i := start; i < len(data)-3; i++ {
		if data[i] == 0 && data[i+1] == 0 && (data[i+2] == 1 || (data[i+2] == 0 && data[i+3] == 1)) {
			return i
		}
	}
	return len(data)
}

// GetCodecInfo returns raw SPS and PPS byte slices
func (p *Parser) GetCodecInfo() (sps []byte, pps []byte) {
	return p.Sps, p.Pps
}
