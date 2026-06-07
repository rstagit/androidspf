package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"math/rand"
	"net"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unsafe"
)

const (
	Version            = "4.0.0"
	BufferSize         = 1 << 20
	FailoverThreshold  = 3
	FailoverWindow     = 30.0
	MaxConcurrentConns = 32768
	DataLogInterval    = 262144
	TCPReadBuffer      = 4 << 20
	TCPWriteBuffer     = 4 << 20
)

var bufPool = sync.Pool{
	New: func() interface{} {
		buf := make([]byte, BufferSize)
		return &buf
	},
}

func init() {
	runtime.GOMAXPROCS(runtime.NumCPU())
}

const (
	EvConnOpen       = "CONN_OPEN"
	EvConnClose      = "CONN_CLOSE"
	EvConnError      = "CONN_ERROR"
	EvTLSParsed      = "TLS_PARSED"
	EvTLSUnknown     = "TLS_UNKNOWN"
	EvFragStart      = "FRAG_START"
	EvFragPiece      = "FRAG_PIECE"
	EvFakeSNISend    = "FAKE_SNI_SEND"
	EvFakeSNITTL     = "FAKE_SNI_TTL"
	EvRealHelloSend  = "REAL_HELLO_SEND"
	EvServerResponse = "SERVER_RESPONSE"
	EvDPIBlocked     = "DPI_BLOCKED"
	EvDPIBypassOK    = "DPI_BYPASS_OK"
	EvDataC2S        = "DATA_C2S"
	EvDataS2C        = "DATA_S2C"
)

type PacketEvent struct {
	Event  string
	ConnID string
	Data   map[string]interface{}
}

type ConnState struct {
	ConnID        string
	ClientAddr    string
	ServerAddr    string
	RealSNI       string
	FakeSNI       string
	Method        string
	FragCount     int
	FragSizes     []int
	BytesC2S      int64
	BytesS2C      int64
	Status        string
	StartTime     time.Time
	EndTime       time.Time
	ServerReplied bool
}

func fmtBytes(n int64) string {
	switch {
	case n < 1024:
		return fmt.Sprintf("%dB", n)
	case n < 1024*1024:
		return fmt.Sprintf("%.1fKB", float64(n)/1024)
	default:
		return fmt.Sprintf("%.2fMB", float64(n)/1024/1024)
	}
}

type PacketMonitor struct {
	mu           sync.RWMutex
	conns        map[string]*ConnState
	maxConns     int
	quiet        bool
	totalConns   int64
	totalBlocked int64
	totalOK      int64
	totalC2S     int64
	totalS2C     int64
	startTime    time.Time
	connOrder    []string
}

func NewPacketMonitor(maxConns int, quiet bool) *PacketMonitor {
	return &PacketMonitor{
		conns:     make(map[string]*ConnState),
		maxConns:  maxConns,
		quiet:     quiet,
		startTime: time.Now(),
	}
}

func (m *PacketMonitor) Emit(ev PacketEvent) {
	m.mu.Lock()
	m.apply(ev)
	m.mu.Unlock()
	if !m.quiet {
		m.printEvent(ev)
	}
}

func (m *PacketMonitor) apply(ev PacketEvent) {
	cid := ev.ConnID
	cs := m.conns[cid]

	switch ev.Event {
	case EvConnOpen:
		atomic.AddInt64(&m.totalConns, 1)
		cs = &ConnState{
			ConnID:     cid,
			ClientAddr: strVal(ev.Data, "client"),
			ServerAddr: strVal(ev.Data, "server"),
			FakeSNI:    strVal(ev.Data, "fake_sni"),
			Method:     strVal(ev.Data, "method"),
			Status:     "connecting",
			StartTime:  time.Now(),
		}
		m.conns[cid] = cs
		m.connOrder = append(m.connOrder, cid)
		for len(m.connOrder) > m.maxConns {
			oldest := m.connOrder[0]
			m.connOrder = m.connOrder[1:]
			delete(m.conns, oldest)
		}
		return
	}

	if cs == nil {
		return
	}

	switch ev.Event {
	case EvTLSParsed:
		cs.RealSNI = strVal(ev.Data, "sni")
	case EvFragStart:
		if v, ok := ev.Data["count"]; ok {
			cs.FragCount = toInt(v)
		}
		if v, ok := ev.Data["sizes"]; ok {
			cs.FragSizes = toIntSlice(v)
		}
	case EvServerResponse:
		cs.ServerReplied = true
		cs.Status = "active"
		atomic.AddInt64(&m.totalOK, 1)
	case EvDPIBlocked:
		cs.Status = "blocked"
		atomic.AddInt64(&m.totalBlocked, 1)
	case EvDPIBypassOK:
		cs.Status = "active"
	case EvDataC2S:
		n := int64(toInt(ev.Data["bytes"]))
		atomic.AddInt64(&cs.BytesC2S, n)
		atomic.AddInt64(&m.totalC2S, n)
	case EvDataS2C:
		n := int64(toInt(ev.Data["bytes"]))
		atomic.AddInt64(&cs.BytesS2C, n)
		atomic.AddInt64(&m.totalS2C, n)
	case EvConnClose:
		cs.Status = "closed"
		cs.EndTime = time.Now()
	case EvConnError:
		cs.Status = "error"
		cs.EndTime = time.Now()
	}
}

func shortID(cid string) string {
	if len(cid) > 8 {
		return cid[:8]
	}
	return cid
}

func (m *PacketMonitor) printEvent(ev PacketEvent) {
	cid := shortID(ev.ConnID)

	m.mu.RLock()
	cs := m.conns[ev.ConnID]
	m.mu.RUnlock()

	switch ev.Event {
	case EvConnOpen:
		emitLog(fmt.Sprintf("[NEW CONN] %s | %s -> %s | method=%s",
			cid, strVal(ev.Data, "client"), strVal(ev.Data, "server"), strVal(ev.Data, "method")))
	case EvTLSParsed:
		emitLog(fmt.Sprintf("[TLS HELLO] %s | real SNI=%s | size=%dB",
			cid, strVal(ev.Data, "sni"), toInt(ev.Data["size"])))
	case EvTLSUnknown:
		emitLog(fmt.Sprintf("[RAW DATA] %s | %dB (not TLS ClientHello)", cid, toInt(ev.Data["size"])))
	case EvFragStart:
		count := toInt(ev.Data["count"])
		sizes := toIntSlice(ev.Data["sizes"])
		parts := make([]string, len(sizes))
		for i, s := range sizes {
			parts[i] = fmt.Sprintf("%dB", s)
		}
		emitLog(fmt.Sprintf("[FRAGMENT] %s | strategy=%s | %d pieces: %s",
			cid, strVal(ev.Data, "strategy"), count, strings.Join(parts, " + ")))
	case EvFragPiece:
		idx := toInt(ev.Data["index"]) + 1
		total := toInt(ev.Data["total"])
		size := toInt(ev.Data["size"])
		delay := toFloat(ev.Data["delay"])
		delayStr := ""
		if delay > 0 {
			delayStr = fmt.Sprintf(" delay=%.0fms", delay*1000)
		}
		sent := "sent"
		if b, ok := ev.Data["sent"].(bool); ok && !b {
			sent = "failed"
		}
		emitLog(fmt.Sprintf("[PKT %d/%d] %s | %dB %s%s", idx, total, cid, size, sent, delayStr))
	case EvFakeSNISend:
		emitLog(fmt.Sprintf("[FAKE SNI] %s | fake=%s | method=%s",
			cid, strVal(ev.Data, "sni"), strVal(ev.Data, "method")))
	case EvFakeSNITTL:
		emitLog(fmt.Sprintf("[TTL TRICK] %s | fake SNI=%s TTL=%v",
			cid, strVal(ev.Data, "sni"), ev.Data["ttl"]))
	case EvRealHelloSend:
		emitLog(fmt.Sprintf("[REAL HELLO] %s | %dB -> server", cid, toInt(ev.Data["size"])))
	case EvServerResponse:
		emitLog(fmt.Sprintf("[OK] %s | SERVER REPLIED %dB | SNI spoof active",
			cid, toInt(ev.Data["size"])))
	case EvDPIBlocked:
		reason := strVal(ev.Data, "reason")
		if reason == "" {
			reason = "no response"
		}
		emitLog(fmt.Sprintf("[BLOCKED] %s | DPI blocked: %s", cid, reason))
	case EvDPIBypassOK:
		emitLog(fmt.Sprintf("[OK] %s | SNI spoof confirmed — bypass active", cid))
	case EvDataC2S:
		n := int64(toInt(ev.Data["bytes"]))
		if n > 0 {
			emitLog(fmt.Sprintf("[C->S] %s | %s", cid, fmtBytes(n)))
		}
	case EvDataS2C:
		n := int64(toInt(ev.Data["bytes"]))
		if n > 0 {
			emitLog(fmt.Sprintf("[S->C] %s | %s", cid, fmtBytes(n)))
		}
	case EvConnClose:
		dur := ""
		if cs != nil {
			d := time.Since(cs.StartTime)
			spoof := "SNI spoof: no response"
			if cs.ServerReplied {
				spoof = "SNI spoof: OK"
			}
			dur = fmt.Sprintf(" | duration=%.1fs | up=%s down=%s | %s",
				d.Seconds(), fmtBytes(cs.BytesC2S), fmtBytes(cs.BytesS2C), spoof)
		}
		emitLog(fmt.Sprintf("[CLOSED] %s%s", cid, dur))
	case EvConnError:
		emitLog(fmt.Sprintf("[ERR] %s | %s", cid, strVal(ev.Data, "error")))
	}
}

func (m *PacketMonitor) PrintStats() {
	uptime := time.Since(m.startTime).Seconds()
	ok := atomic.LoadInt64(&m.totalOK)
	blocked := atomic.LoadInt64(&m.totalBlocked)
	total := ok + blocked
	spoofRate := 0
	if total > 0 {
		spoofRate = int(ok * 100 / total)
	}
	emitLog(fmt.Sprintf("[INFO] Session stats v%s | uptime=%.0fs | conns=%d | ok=%d blocked=%d | spoof=%d%% | traffic up=%s down=%s",
		Version, uptime, atomic.LoadInt64(&m.totalConns), ok, blocked, spoofRate,
		fmtBytes(atomic.LoadInt64(&m.totalC2S)), fmtBytes(atomic.LoadInt64(&m.totalS2C))))
}

var (
	globalMonitor *PacketMonitor
	monitorMu     sync.Mutex
)

func GetMonitor() *PacketMonitor {
	monitorMu.Lock()
	defer monitorMu.Unlock()
	return globalMonitor
}

func InitMonitor(quiet bool) *PacketMonitor {
	monitorMu.Lock()
	defer monitorMu.Unlock()
	globalMonitor = NewPacketMonitor(50, quiet)
	return globalMonitor
}

var (
	cipherSuites, _ = hex.DecodeString(
		"0024" +
			"1302" + "1303" + "1301" + "c02c" + "c030" + "c02b" + "c02f" +
			"cca9" + "cca8" + "c024" + "c028" + "c023" + "c027" +
			"009f" + "009e" + "006b" + "0067" + "00ff")
	supportedGroups, _ = hex.DecodeString(
		"000a" + "0016" + "0014" +
			"001d" + "0017" + "001e" + "0019" + "0018" +
			"0100" + "0101" + "0102" + "0103" + "0104")
	signatureAlgorithms, _ = hex.DecodeString(
		"000d" + "002a" + "0028" +
			"0403" + "0503" + "0603" + "0807" + "0808" + "0809" + "080a" + "080b" +
			"0804" + "0805" + "0806" + "0401" + "0501" + "0601" +
			"0303" + "0301" + "0302" + "0402" + "0502" + "0602")
	ecPointFormats, _       = hex.DecodeString("000b" + "0004" + "0300" + "0102")
	sessionTicket, _        = hex.DecodeString("0023" + "0000")
	alpn, _                 = hex.DecodeString("0010" + "000e" + "000c" + "0268" + "3208" + "6874" + "7470" + "2f31" + "2e31")
	encryptThenMAC, _       = hex.DecodeString("0016" + "0000")
	extendedMasterSecret, _ = hex.DecodeString("0017" + "0000")
	supportedVersions, _    = hex.DecodeString("002b" + "0005" + "04" + "0304" + "0303")
	pskKeyExchange, _       = hex.DecodeString("002d" + "0002" + "0101")
)

type TLSExtensionBuilder struct{}

func (TLSExtensionBuilder) buildSNI(sni string) []byte {
	sniBytes := []byte(sni)
	entry := make([]byte, 3+len(sniBytes))
	entry[0] = 0
	binary.BigEndian.PutUint16(entry[1:], uint16(len(sniBytes)))
	copy(entry[3:], sniBytes)
	nameList := make([]byte, 2+len(entry))
	binary.BigEndian.PutUint16(nameList, uint16(len(entry)))
	copy(nameList[2:], entry)
	result := make([]byte, 4+len(nameList))
	binary.BigEndian.PutUint16(result, 0x0000)
	binary.BigEndian.PutUint16(result[2:], uint16(len(nameList)))
	copy(result[4:], nameList)
	return result
}

func (TLSExtensionBuilder) buildKeyShare(publicKey []byte) []byte {
	if publicKey == nil {
		publicKey = make([]byte, 32)
		rand.Read(publicKey)
	}
	entry := make([]byte, 4+len(publicKey))
	binary.BigEndian.PutUint16(entry, 0x001D)
	binary.BigEndian.PutUint16(entry[2:], 32)
	copy(entry[4:], publicKey)
	data := make([]byte, 2+len(entry))
	binary.BigEndian.PutUint16(data, uint16(len(entry)))
	copy(data[2:], entry)
	result := make([]byte, 4+len(data))
	binary.BigEndian.PutUint16(result, 0x0033)
	binary.BigEndian.PutUint16(result[2:], uint16(len(data)))
	copy(result[4:], data)
	return result
}

func (TLSExtensionBuilder) buildPadding(targetLength, currentLength int) []byte {
	paddingNeeded := targetLength - currentLength - 4
	if paddingNeeded < 0 {
		return nil
	}
	result := make([]byte, 4+paddingNeeded)
	binary.BigEndian.PutUint16(result, 0x0015)
	binary.BigEndian.PutUint16(result[2:], uint16(paddingNeeded))
	return result
}

type ClientHelloBuilder struct {
	ext TLSExtensionBuilder
}

func (b ClientHelloBuilder) BuildSNIExtension(sni string) []byte {
	return b.ext.buildSNI(sni)
}

func (b ClientHelloBuilder) BuildKeyShareExtension(publicKey []byte) []byte {
	return b.ext.buildKeyShare(publicKey)
}

func (b ClientHelloBuilder) BuildPaddingExtension(targetLength, currentLength int) []byte {
	return b.ext.buildPadding(targetLength, currentLength)
}

func (b ClientHelloBuilder) BuildClientHello(sni string, sessionID, randomBytes, keyShare []byte, targetSize int) []byte {
	if sessionID == nil {
		sessionID = make([]byte, 32)
		rand.Read(sessionID)
	}
	if randomBytes == nil {
		randomBytes = make([]byte, 32)
		rand.Read(randomBytes)
	}
	if targetSize == 0 {
		targetSize = 517
	}

	clientVersion := []byte{0x03, 0x03}
	sessionIDField := append([]byte{byte(len(sessionID))}, sessionID...)
	compression := []byte{0x01, 0x00}
	sniExt := b.BuildSNIExtension(sni)
	keyShareExt := b.BuildKeyShareExtension(keyShare)

	extensions := concat(sniExt, ecPointFormats, supportedGroups,
		sessionTicket, alpn, encryptThenMAC,
		extendedMasterSecret, signatureAlgorithms,
		supportedVersions, pskKeyExchange, keyShareExt)

	handshakeBodyNoPad := concat(clientVersion, randomBytes, sessionIDField, cipherSuites, compression)
	totalSoFar := 4 + len(handshakeBodyNoPad) + 2 + len(extensions)
	recordSoFar := 5 + totalSoFar
	paddingExt := b.BuildPaddingExtension(targetSize, recordSoFar)
	if paddingExt != nil {
		extensions = concat(extensions, paddingExt)
	}

	extWithLen := make([]byte, 2+len(extensions))
	binary.BigEndian.PutUint16(extWithLen, uint16(len(extensions)))
	copy(extWithLen[2:], extensions)

	handshakeBody := concat(handshakeBodyNoPad, extWithLen)
	hsLen := len(handshakeBody)
	handshake := make([]byte, 4+hsLen)
	handshake[0] = 0x01
	handshake[1] = byte(hsLen >> 16)
	handshake[2] = byte(hsLen >> 8)
	handshake[3] = byte(hsLen)
	copy(handshake[4:], handshakeBody)

	record := make([]byte, 5+len(handshake))
	record[0] = 0x16
	record[1] = 0x03
	record[2] = 0x01
	binary.BigEndian.PutUint16(record[3:], uint16(len(handshake)))
	copy(record[5:], handshake)
	return record
}

func (ClientHelloBuilder) ParseClientHello(data []byte) map[string]interface{} {
	result := make(map[string]interface{})
	if len(data) < 5 {
		return result
	}
	contentType := data[0]
	if contentType != 0x16 {
		return result
	}
	pos := 5
	if pos+4 > len(data) {
		return result
	}
	hsType := data[pos]
	hsLen := int(data[pos+1])<<16 | int(data[pos+2])<<8 | int(data[pos+3])
	pos += 4
	if hsType != 0x01 || hsLen == 0 {
		return result
	}
	result["handshake_type"] = "ClientHello"
	pos += 2
	if pos+32 > len(data) {
		return result
	}
	pos += 32
	if pos >= len(data) {
		return result
	}
	sessLen := int(data[pos])
	pos += 1 + sessLen
	if pos+2 > len(data) {
		return result
	}
	csLen := int(binary.BigEndian.Uint16(data[pos:]))
	pos += 2 + csLen
	if pos >= len(data) {
		return result
	}
	compLen := int(data[pos])
	pos += 1 + compLen
	if pos+2 > len(data) {
		return result
	}
	extLen := int(binary.BigEndian.Uint16(data[pos:]))
	pos += 2
	extEnd := pos + extLen
	for pos+4 <= extEnd && pos+4 <= len(data) {
		extType := binary.BigEndian.Uint16(data[pos:])
		extDataLen := int(binary.BigEndian.Uint16(data[pos+2:]))
		if pos+4+extDataLen > len(data) {
			break
		}
		extData := data[pos+4 : pos+4+extDataLen]
		pos += 4 + extDataLen
		if extType == 0x0000 && len(extData) >= 5 {
			nameLen := int(binary.BigEndian.Uint16(extData[3:5]))
			if 5+nameLen <= len(extData) {
				result["sni"] = string(extData[5 : 5+nameLen])
			}
		}
	}
	return result
}

type FragmentEngine struct{}

func (FragmentEngine) Fragment(data []byte, strategy string) [][]byte {
	if strategy == "none" || len(data) < 10 {
		return [][]byte{data}
	}
	switch strategy {
	case "sni_split":
		return FragmentEngine{}.atSNI(data)
	case "half":
		mid := len(data) / 2
		return [][]byte{data[:mid], data[mid:]}
	case "multi":
		return FragmentEngine{}.multi(data, 24)
	case "tls_record_frag":
		return FragmentEngine{}.tlsRecord(data)
	}
	return [][]byte{data}
}

func (FragmentEngine) findSNIOffset(data []byte) (int, int) {
	for pos := 0; pos < len(data)-10; pos++ {
		if data[pos] != 0x00 || data[pos+1] != 0x00 {
			continue
		}
		if pos+9 >= len(data) {
			continue
		}
		extLen := int(binary.BigEndian.Uint16(data[pos+2:]))
		if extLen <= 4 || extLen >= 256 || pos+4+extLen > len(data) {
			continue
		}
		nameType := data[pos+6]
		nameLen := int(binary.BigEndian.Uint16(data[pos+7:]))
		if nameType == 0 && nameLen > 0 && nameLen < 256 {
			sniStart := pos + 9
			if sniStart+nameLen > len(data) {
				continue
			}
			sniData := data[sniStart : sniStart+nameLen]
			valid := true
			for _, b := range sniData {
				if b < 0x20 || b >= 0x7F {
					valid = false
					break
				}
			}
			if valid {
				return sniStart, nameLen
			}
		}
	}
	return -1, 0
}

func (e FragmentEngine) atSNI(data []byte) [][]byte {
	sniOffset, sniLen := e.findSNIOffset(data)
	if sniOffset < 0 {
		mid := len(data) / 2
		return [][]byte{data[:mid], data[mid:]}
	}
	splitPoint := sniOffset + sniLen/2
	return [][]byte{data[:splitPoint], data[splitPoint:]}
}

func (FragmentEngine) multi(data []byte, chunkSize int) [][]byte {
	var result [][]byte
	for i := 0; i < len(data); i += chunkSize {
		end := i + chunkSize
		if end > len(data) {
			end = len(data)
		}
		result = append(result, data[i:end])
	}
	return result
}

func (FragmentEngine) tlsRecord(data []byte) [][]byte {
	if len(data) < 6 || data[0] != 0x16 {
		return [][]byte{data}
	}
	recordVersion := data[1:3]
	handshakeData := data[5:]
	mid := len(handshakeData) / 2
	part1, part2 := handshakeData[:mid], handshakeData[mid:]
	record1 := make([]byte, 5+len(part1))
	record1[0] = 0x16
	copy(record1[1:], recordVersion)
	binary.BigEndian.PutUint16(record1[3:], uint16(len(part1)))
	copy(record1[5:], part1)
	record2 := make([]byte, 5+len(part2))
	record2[0] = 0x16
	copy(record2[1:], recordVersion)
	binary.BigEndian.PutUint16(record2[3:], uint16(len(part2)))
	copy(record2[5:], part2)
	return [][]byte{record1, record2}
}

type BypassStrategy interface {
	Name() string
	Apply(clientConn, serverConn net.Conn, fakeSNI string, firstData []byte) bool
}

type FragmentBypass struct {
	strategy      string
	fragmentDelay float64
	tcpNoDelay    bool
	engine        FragmentEngine
}

func NewFragmentBypass(strategy string, fragmentDelay float64, tcpNoDelay bool) *FragmentBypass {
	return &FragmentBypass{strategy: strategy, fragmentDelay: fragmentDelay, tcpNoDelay: tcpNoDelay}
}

func (f *FragmentBypass) Name() string { return "fragment" }

func (f *FragmentBypass) Apply(_ net.Conn, serverConn net.Conn, _ string, firstData []byte) bool {
	if tc, ok := serverConn.(*net.TCPConn); ok && f.tcpNoDelay {
		tc.SetNoDelay(true)
	}
	fragments := f.engine.Fragment(firstData, f.strategy)
	for i, frag := range fragments {
		if _, err := serverConn.Write(frag); err != nil {
			return false
		}
		if i < len(fragments)-1 && f.fragmentDelay > 0 {
			time.Sleep(time.Duration(f.fragmentDelay * float64(time.Second)))
		}
	}
	if tc, ok := serverConn.(*net.TCPConn); ok && f.tcpNoDelay {
		tc.SetNoDelay(false)
	}
	return true
}

type FakeSNIBypass struct {
	useTTLTrick      bool
	fragmentStrategy string
	engine           FragmentEngine
}

func NewFakeSNIBypass(useTTLTrick bool, fragmentStrategy string) *FakeSNIBypass {
	return &FakeSNIBypass{useTTLTrick: useTTLTrick, fragmentStrategy: fragmentStrategy}
}

func (f *FakeSNIBypass) Name() string { return "fake_sni" }

func (f *FakeSNIBypass) Apply(_ net.Conn, serverConn net.Conn, fakeSNI string, firstData []byte) bool {
	if f.useTTLTrick {
		return f.ttlTrickAndFragment(serverConn, fakeSNI, firstData)
	}
	return f.fragmentFallback(serverConn, firstData)
}

func (f *FakeSNIBypass) ttlTrickAndFragment(serverConn net.Conn, fakeSNI string, firstData []byte) bool {
	if tc, ok := serverConn.(*net.TCPConn); ok {
		tc.SetNoDelay(true)
	}
	remoteAddr := serverConn.RemoteAddr().String()
	fakeHello := ClientHelloBuilder{}.BuildClientHello(fakeSNI, nil, nil, nil, 517)
	for i := 0; i < 3; i++ {
		probe, err := net.DialTimeout("tcp", remoteAddr, 300*time.Millisecond)
		if err == nil {
			probe.Write(fakeHello)
			probe.Close()
			break
		}
	}
	time.Sleep(50 * time.Millisecond)
	fragments := f.engine.Fragment(firstData, "sni_split")
	for i, frag := range fragments {
		if _, err := serverConn.Write(frag); err != nil {
			return false
		}
		if i < len(fragments)-1 {
			time.Sleep(100 * time.Millisecond)
		}
	}
	if tc, ok := serverConn.(*net.TCPConn); ok {
		tc.SetNoDelay(false)
	}
	return true
}

func (f *FakeSNIBypass) fragmentFallback(serverConn net.Conn, firstData []byte) bool {
	if tc, ok := serverConn.(*net.TCPConn); ok {
		tc.SetNoDelay(true)
	}
	fragments := f.engine.Fragment(firstData, "sni_split")
	for i, frag := range fragments {
		if _, err := serverConn.Write(frag); err != nil {
			return false
		}
		if i < len(fragments)-1 {
			time.Sleep(100 * time.Millisecond)
		}
	}
	if tc, ok := serverConn.(*net.TCPConn); ok {
		tc.SetNoDelay(false)
	}
	return true
}

type CombinedBypass struct {
	fragmentStrategy string
	useTTLTrick      bool
	fragmentDelay    float64
	fakeFirst        bool
	engine           FragmentEngine
}

func NewCombinedBypass(fragmentStrategy string, useTTLTrick bool, fragmentDelay float64, fakeFirst bool) *CombinedBypass {
	return &CombinedBypass{
		fragmentStrategy: fragmentStrategy,
		useTTLTrick:      useTTLTrick,
		fragmentDelay:    fragmentDelay,
		fakeFirst:        fakeFirst,
	}
}

func (cb *CombinedBypass) Name() string { return "combined" }

func (cb *CombinedBypass) Apply(_ net.Conn, serverConn net.Conn, fakeSNI string, firstData []byte) bool {
	if tc, ok := serverConn.(*net.TCPConn); ok {
		tc.SetNoDelay(true)
	}
	if cb.fakeFirst && cb.useTTLTrick {
		remoteAddr := serverConn.RemoteAddr().String()
		fakeHello := ClientHelloBuilder{}.BuildClientHello(fakeSNI, nil, nil, nil, 517)
		for i := 0; i < 3; i++ {
			probe, err := net.DialTimeout("tcp", remoteAddr, 300*time.Millisecond)
			if err == nil {
				probe.Write(fakeHello)
				probe.Close()
				break
			}
		}
		time.Sleep(50 * time.Millisecond)
	}
	fragments := cb.engine.Fragment(firstData, cb.fragmentStrategy)
	for i, frag := range fragments {
		if _, err := serverConn.Write(frag); err != nil {
			return false
		}
		if i < len(fragments)-1 && cb.fragmentDelay > 0 {
			time.Sleep(time.Duration(cb.fragmentDelay * float64(time.Second)))
		}
	}
	if tc, ok := serverConn.(*net.TCPConn); ok {
		tc.SetNoDelay(false)
	}
	return true
}

type ConnectionTracker struct {
	mu       sync.Mutex
	failures map[string][]time.Time
}

func NewConnectionTracker() *ConnectionTracker {
	return &ConnectionTracker{failures: make(map[string][]time.Time)}
}

func (ct *ConnectionTracker) RecordFailure(ip string) int {
	ct.mu.Lock()
	defer ct.mu.Unlock()
	now := time.Now()
	cutoff := now.Add(-FailoverWindow * time.Second)
	if ct.failures[ip] == nil {
		ct.failures[ip] = []time.Time{}
	}
	filtered := ct.failures[ip][:0]
	for _, t := range ct.failures[ip] {
		if t.After(cutoff) {
			filtered = append(filtered, t)
		}
	}
	filtered = append(filtered, now)
	ct.failures[ip] = filtered
	return len(filtered)
}

func (ct *ConnectionTracker) RecordSuccess(ip string) {
	ct.mu.Lock()
	defer ct.mu.Unlock()
	delete(ct.failures, ip)
}

var (
	connTracker    = NewConnectionTracker()
	connCounter    int64
	logQueue       []string
	logQueueMu     sync.Mutex
	activeSessions sync.Map
	sessionCounter int64
)

func emitLog(msg string) {
	logQueueMu.Lock()
	logQueue = append(logQueue, msg)
	if len(logQueue) > 1000 {
		logQueue = logQueue[len(logQueue)-500:]
	}
	logQueueMu.Unlock()
}

func newConnID() string {
	n := atomic.AddInt64(&connCounter, 1)
	return fmt.Sprintf("C%06d", n)
}

func buildStrategy(method string) BypassStrategy {
	switch method {
	case "fragment":
		return NewFragmentBypass("sni_split", 0.1, true)
	case "fake_sni":
		return NewFakeSNIBypass(true, "sni_split")
	case "combined":
		return NewCombinedBypass("sni_split", true, 0.1, true)
	}
	return NewCombinedBypass("sni_split", true, 0.1, true)
}

func resolveHost(host string) string {
	if net.ParseIP(host) != nil {
		return host
	}
	addrs, err := net.LookupHost(host)
	if err != nil || len(addrs) == 0 {
		return host
	}
	return addrs[0]
}

type PipeWorker struct {
	src        net.Conn
	dst        net.Conn
	connID     string
	mon        *PacketMonitor
	evType     string
	acc        *int64
	onFirst    func()
	closeOther net.Conn
}

func (pw *PipeWorker) run(wg *sync.WaitGroup) {
	defer wg.Done()
	bufPtr := bufPool.Get().(*[]byte)
	buf := *bufPtr
	defer bufPool.Put(bufPtr)
	first := true
	for {
		nr, err := pw.src.Read(buf)
		if nr > 0 {
			if _, wErr := pw.dst.Write(buf[:nr]); wErr != nil {
				break
			}
			if first && pw.onFirst != nil {
				pw.onFirst()
				first = false
			}
			if pw.mon != nil && pw.acc != nil {
				n64 := int64(nr)
				prev := atomic.AddInt64(pw.acc, n64) - n64
				if prev/DataLogInterval != (prev+n64)/DataLogInterval {
					pw.mon.Emit(PacketEvent{Event: pw.evType, ConnID: pw.connID, Data: map[string]interface{}{"bytes": nr}})
				}
			}
		}
		if err != nil {
			break
		}
	}
	pw.closeOther.Close()
}

type ProxyServer struct {
	listenAddr string
	remoteAddr string
	connectIP  string
	fakeSNI    string
	strategy   BypassStrategy
	semaphore  chan struct{}
	dialer     *net.Dialer
	listener   net.Listener
	stopCh     chan struct{}
}

func (ps *ProxyServer) Start() error {
	ln, err := net.Listen("tcp", ps.listenAddr)
	if err != nil {
		return err
	}
	ps.listener = ln
	emitLog(fmt.Sprintf("[INFO] RSTA v%s | %s -> %s | fake=%s | method=%s",
		Version, ps.listenAddr, ps.remoteAddr, ps.fakeSNI, ps.strategy.Name()))
	go ps.acceptLoop()
	return nil
}

func (ps *ProxyServer) Stop() {
	close(ps.stopCh)
	if ps.listener != nil {
		ps.listener.Close()
	}
	if m := GetMonitor(); m != nil {
		m.PrintStats()
	}
}

func (ps *ProxyServer) acceptLoop() {
	for {
		conn, err := ps.listener.Accept()
		if err != nil {
			select {
			case <-ps.stopCh:
				return
			default:
				continue
			}
		}
		if tc, ok := conn.(*net.TCPConn); ok {
			tc.SetReadBuffer(TCPReadBuffer)
			tc.SetWriteBuffer(TCPWriteBuffer)
			tc.SetNoDelay(true)
		}
		ps.semaphore <- struct{}{}
		go func(c net.Conn) {
			defer func() { <-ps.semaphore }()
			ps.handleConnection(c)
		}(conn)
	}
}

func (ps *ProxyServer) handleConnection(incoming net.Conn) {
	mon := GetMonitor()
	connID := newConnID()
	clientStr := incoming.RemoteAddr().String()

	if mon != nil {
		mon.Emit(PacketEvent{Event: EvConnOpen, ConnID: connID, Data: map[string]interface{}{
			"client": clientStr, "server": ps.remoteAddr,
			"fake_sni": ps.fakeSNI, "method": ps.strategy.Name(),
		}})
	}

	defer func() {
		incoming.Close()
		if mon != nil {
			mon.Emit(PacketEvent{Event: EvConnClose, ConnID: connID})
		}
	}()

	incoming.SetDeadline(time.Now().Add(30 * time.Second))
	bufPtr := bufPool.Get().(*[]byte)
	buf := *bufPtr
	n, err := incoming.Read(buf)
	if err != nil || n == 0 {
		bufPool.Put(bufPtr)
		return
	}
	firstData := make([]byte, n)
	copy(firstData, buf[:n])
	bufPool.Put(bufPtr)
	incoming.SetDeadline(time.Time{})

	builder := ClientHelloBuilder{}
	parsed := builder.ParseClientHello(firstData)
	clientSNI := ""
	if s, ok := parsed["sni"].(string); ok {
		clientSNI = s
	}

	if mon != nil {
		if parsed["handshake_type"] == "ClientHello" {
			mon.Emit(PacketEvent{Event: EvTLSParsed, ConnID: connID, Data: map[string]interface{}{
				"sni": clientSNI, "size": n,
			}})
		} else {
			mon.Emit(PacketEvent{Event: EvTLSUnknown, ConnID: connID, Data: map[string]interface{}{
				"size": n,
			}})
		}
	}

	outgoing, err := ps.dialer.Dial("tcp", ps.remoteAddr)
	if err != nil {
		connTracker.RecordFailure(ps.connectIP)
		if mon != nil {
			mon.Emit(PacketEvent{Event: EvConnError, ConnID: connID, Data: map[string]interface{}{
				"error": err.Error(),
			}})
		}
		return
	}
	defer outgoing.Close()

	if tc, ok := outgoing.(*net.TCPConn); ok {
		tc.SetReadBuffer(TCPReadBuffer)
		tc.SetWriteBuffer(TCPWriteBuffer)
		tc.SetKeepAlive(true)
		tc.SetKeepAlivePeriod(30 * time.Second)
		tc.SetNoDelay(true)
	}

	success := ps.applyStrategyInstrumented(incoming, outgoing, firstData, connID, mon)
	if !success {
		emitLog(fmt.Sprintf("[WARN] %s strategy failed, sending raw", shortID(connID)))
		outgoing.Write(firstData)
	}

	var (
		serverResponded int32
		c2sAcc          int64
		s2cAcc          int64
		wg              sync.WaitGroup
	)

	wg.Add(2)

	c2sWorker := &PipeWorker{
		src:        incoming,
		dst:        outgoing,
		connID:     connID,
		mon:        mon,
		evType:     EvDataC2S,
		acc:        &c2sAcc,
		closeOther: outgoing,
	}
	s2cWorker := &PipeWorker{
		src:        outgoing,
		dst:        incoming,
		connID:     connID,
		mon:        mon,
		evType:     EvDataS2C,
		acc:        &s2cAcc,
		closeOther: incoming,
		onFirst: func() {
			if atomic.CompareAndSwapInt32(&serverResponded, 0, 1) {
				connTracker.RecordSuccess(ps.connectIP)
				if mon != nil {
					mon.Emit(PacketEvent{Event: EvServerResponse, ConnID: connID, Data: map[string]interface{}{"size": 0}})
				}
			}
		},
	}

	go c2sWorker.run(&wg)
	go s2cWorker.run(&wg)

	wg.Wait()

	if atomic.LoadInt32(&serverResponded) == 0 {
		connTracker.RecordFailure(ps.connectIP)
		if mon != nil {
			mon.Emit(PacketEvent{Event: EvDPIBlocked, ConnID: connID, Data: map[string]interface{}{
				"reason": "server never responded",
			}})
		}
	}
}

func (ps *ProxyServer) applyStrategyInstrumented(
	clientConn, serverConn net.Conn,
	firstData []byte,
	connID string,
	mon *PacketMonitor,
) bool {
	name := ps.strategy.Name()
	switch name {
	case "fragment":
		fs := ps.strategy.(*FragmentBypass)
		fragments := fs.engine.Fragment(firstData, fs.strategy)
		if mon != nil {
			sizes := make([]int, len(fragments))
			for i, f := range fragments {
				sizes[i] = len(f)
			}
			mon.Emit(PacketEvent{Event: EvFragStart, ConnID: connID, Data: map[string]interface{}{
				"count": len(fragments), "sizes": sizes, "strategy": fs.strategy,
			}})
		}
		if tc, ok := serverConn.(*net.TCPConn); ok {
			tc.SetNoDelay(true)
		}
		for i, frag := range fragments {
			_, err := serverConn.Write(frag)
			sent := err == nil
			if mon != nil {
				delay := 0.0
				if i < len(fragments)-1 {
					delay = fs.fragmentDelay
				}
				mon.Emit(PacketEvent{Event: EvFragPiece, ConnID: connID, Data: map[string]interface{}{
					"index": i, "total": len(fragments),
					"size": len(frag), "sent": sent, "delay": delay,
				}})
			}
			if !sent {
				return false
			}
			if i < len(fragments)-1 && fs.fragmentDelay > 0 {
				time.Sleep(time.Duration(fs.fragmentDelay * float64(time.Second)))
			}
		}
		if tc, ok := serverConn.(*net.TCPConn); ok {
			tc.SetNoDelay(false)
		}
		return true

	case "fake_sni":
		fsb := ps.strategy.(*FakeSNIBypass)
		if fsb.useTTLTrick && mon != nil {
			mon.Emit(PacketEvent{Event: EvFakeSNITTL, ConnID: connID, Data: map[string]interface{}{
				"sni": ps.fakeSNI, "ttl": 2,
			}})
		} else if mon != nil {
			mon.Emit(PacketEvent{Event: EvFakeSNISend, ConnID: connID, Data: map[string]interface{}{
				"sni": ps.fakeSNI, "method": "fragment-fallback",
			}})
		}
		return ps.strategy.Apply(clientConn, serverConn, ps.fakeSNI, firstData)

	case "combined":
		cb := ps.strategy.(*CombinedBypass)
		if cb.useTTLTrick && mon != nil {
			mon.Emit(PacketEvent{Event: EvFakeSNITTL, ConnID: connID, Data: map[string]interface{}{
				"sni": ps.fakeSNI, "ttl": 2,
			}})
		}
		fragments := cb.engine.Fragment(firstData, cb.fragmentStrategy)
		if mon != nil {
			sizes := make([]int, len(fragments))
			for i, f := range fragments {
				sizes[i] = len(f)
			}
			mon.Emit(PacketEvent{Event: EvFragStart, ConnID: connID, Data: map[string]interface{}{
				"count": len(fragments), "sizes": sizes, "strategy": cb.fragmentStrategy,
			}})
		}
		result := ps.strategy.Apply(clientConn, serverConn, ps.fakeSNI, firstData)
		if mon != nil && result {
			mon.Emit(PacketEvent{Event: EvDPIBypassOK, ConnID: connID})
		}
		return result
	}
	return ps.strategy.Apply(clientConn, serverConn, ps.fakeSNI, firstData)
}

func concat(slices ...[]byte) []byte {
	total := 0
	for _, s := range slices {
		total += len(s)
	}
	result := make([]byte, 0, total)
	for _, s := range slices {
		result = append(result, s...)
	}
	return result
}

func strVal(m map[string]interface{}, key string) string {
	if v, ok := m[key]; ok {
		if s, ok := v.(string); ok {
			return s
		}
	}
	return ""
}

func toInt(v interface{}) int {
	if v == nil {
		return 0
	}
	switch x := v.(type) {
	case int:
		return x
	case int64:
		return int(x)
	case float64:
		return int(x)
	}
	return 0
}

func toFloat(v interface{}) float64 {
	if v == nil {
		return 0
	}
	if x, ok := v.(float64); ok {
		return x
	}
	return 0
}

func toIntSlice(v interface{}) []int {
	if v == nil {
		return nil
	}
	if s, ok := v.([]int); ok {
		return s
	}
	if s, ok := v.([]interface{}); ok {
		result := make([]int, len(s))
		for i, x := range s {
			result[i] = toInt(x)
		}
		return result
	}
	return nil
}

//export SpfStart
func SpfStart(listenPort C.int, remoteEndpoint *C.char, fakeSni *C.char, method *C.char) C.longlong {
	InitMonitor(false)

	id := atomic.AddInt64(&sessionCounter, 1)
	remote := C.GoString(remoteEndpoint)
	host := remote
	port := "443"
	if h, p, err := net.SplitHostPort(remote); err == nil {
		host = h
		port = p
	}
	resolved := resolveHost(host)
	remoteAddr := net.JoinHostPort(resolved, port)

	server := &ProxyServer{
		listenAddr: fmt.Sprintf("127.0.0.1:%d", int(listenPort)),
		remoteAddr: remoteAddr,
		connectIP:  resolved,
		fakeSNI:    C.GoString(fakeSni),
		strategy:   buildStrategy(C.GoString(method)),
		semaphore:  make(chan struct{}, MaxConcurrentConns),
		dialer:     &net.Dialer{Timeout: 15 * time.Second, KeepAlive: 30 * time.Second},
		stopCh:     make(chan struct{}),
	}

	if err := server.Start(); err != nil {
		emitLog(fmt.Sprintf("[ERR] listen failed on %s: %v", server.listenAddr, err))
		return 0
	}

	activeSessions.Store(id, server)
	return C.longlong(id)
}

//export SpfStop
func SpfStop(sessionID C.longlong) {
	id := int64(sessionID)
	if val, loaded := activeSessions.LoadAndDelete(id); loaded {
		server := val.(*ProxyServer)
		server.Stop()
		emitLog("[INFO] tunnel stopped")
	}
}

//export SpfPollLog
func SpfPollLog() *C.char {
	logQueueMu.Lock()
	defer logQueueMu.Unlock()
	if len(logQueue) == 0 {
		return nil
	}
	msg := logQueue[0]
	logQueue = logQueue[1:]
	return C.CString(msg)
}

//export SpfSetLogCallback
func SpfSetLogCallback(callback unsafe.Pointer) {
	_ = callback
}

//export SpfParseSni
func SpfParseSni(data *C.char, length C.int) *C.char {
	bytes := C.GoBytes(unsafe.Pointer(data), length)
	parsed := ClientHelloBuilder{}.ParseClientHello(bytes)
	if sni, ok := parsed["sni"].(string); ok {
		return C.CString(sni)
	}
	return C.CString("")
}

//export SpfVersion
func SpfVersion() *C.char {
	return C.CString(Version)
}

func main() {}
