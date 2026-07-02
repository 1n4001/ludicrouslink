package discovery

import (
	"context"
	"log"

	"github.com/grandcat/zeroconf"
)

type Service struct {
	server *zeroconf.Server
}

func NewService(instanceName string, port int) (*Service, error) {
	// Register the service
	// _http._tcp.local.
	server, err := zeroconf.Register(
		instanceName,
		"_http._tcp",
		"local.",
		port,
		[]string{"txtv=0", "lo=1", "la=2"},
		nil,
	)
	if err != nil {
		return nil, err
	}

	return &Service{server: server}, nil
}

func (s *Service) Shutdown() {
	if s.server != nil {
		s.server.Shutdown()
	}
}

// StartDiscovery is a helper to start advertising and keep running until context cancelled
func StartDiscovery(ctx context.Context, port int) {
	service, err := NewService("LudicrousLink Gateway", port)
	if err != nil {
		log.Printf("Failed to start mDNS service: %v", err)
		return
	}
	defer service.Shutdown()
	
	log.Printf("mDNS service started: LudicrousLink Gateway._http._tcp.local. on port %d", port)

	<-ctx.Done()
	log.Println("Stopping mDNS service...")
}
