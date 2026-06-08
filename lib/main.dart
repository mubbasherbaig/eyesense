import 'dart:async';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

const _method = MethodChannel('com.example.eyesense/tof');
const _events = EventChannel('com.example.eyesense/depth_stream');

void main() => runApp(const EyeSenseApp());

class EyeSenseApp extends StatelessWidget {
  const EyeSenseApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'EyeSense',
        debugShowCheckedModeBanner: false,
        theme: ThemeData.dark(useMaterial3: true).copyWith(
          colorScheme: const ColorScheme.dark(primary: Colors.cyanAccent),
          scaffoldBackgroundColor: const Color(0xFF0D1117),
        ),
        home: const HomeScreen(),
      );
}

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});
  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen>
    with SingleTickerProviderStateMixin {
  late final TabController _tabCtrl;
  StreamSubscription? _sub;

  // ── Local frame state ──────────────────────────────────────────────────────
  Uint8List? _frameData;
  int _localFrames = 0;

  // ── Capture state ──────────────────────────────────────────────────────────
  int?   _captureProgress;   // null = idle, 0-30 = in progress
  String? _captureMsg;       // completion message

  // ── ROI capture state ─────────────────────────────────────────────────────
  bool   _roiSaving  = false;
  bool   _roiSaved   = false;

  // ── Auto-capture state ─────────────────────────────────────────────────────
  bool   _autoCaptureEnabled   = false;
  bool   _autoCaptureTriggered = false;
  int    _distanceMm           = 0;
  String _distanceStatus       = '';   // 'ok' | 'too_close' | 'too_far' | ''
  double _stabilityProgress    = 0.0;

  // ── TCP state ──────────────────────────────────────────────────────────────
  final _ipCtrl   = TextEditingController(text: '192.168.1.100');
  final _portCtrl = TextEditingController(text: '9999');
  String _tcpState   = 'idle';
  String _tcpMessage = 'Enter laptop IP and tap Connect';
  int    _tcpFrames  = 0;

  static const _viewNames  = ['mozart', 'depth', 'confidence', 'gradient', 'n2asym'];
  bool get _isTcpActive =>
      _tcpState == 'connecting' ||
      _tcpState == 'connected'  ||
      _tcpState == 'streaming';

  @override
  void initState() {
    super.initState();
    _tabCtrl = TabController(length: 5, vsync: this);
    _tabCtrl.addListener(_onTabChanged);
    _sub = _events.receiveBroadcastStream().listen(_onEvent);
    _method.invokeMethod('requestPermission').then((_) async {
      await _method.invokeMethod('startCamera');
      await _method.invokeMethod('setView', 'mozart');
    });
  }

  @override
  void dispose() {
    _sub?.cancel();
    _tabCtrl.dispose();
    _ipCtrl.dispose();
    _portCtrl.dispose();
    _method.invokeMethod('stopStreaming');
    _method.invokeMethod('stopCamera');
    super.dispose();
  }

  void _onTabChanged() {
    if (!_tabCtrl.indexIsChanging) return;
    final i = _tabCtrl.index;
    if (i < 3) {
      _method.invokeMethod('setView', _viewNames[i]);
    } else if (i == 3) {
      _method.invokeMethod('setView', 'mozart');
    }
    setState(() {});
  }

  void _onEvent(dynamic event) {
    if (event is! Map || !mounted) return;
    final type = event['type'] as String? ?? '';
    if (type == 'frame') {
      final raw = event['data'];
      if (raw == null) return;
      final bytes = raw is Uint8List
          ? raw
          : Uint8List.fromList((raw as List).cast<int>());
      setState(() {
        _frameData   = bytes;
        _localFrames = (event['frames'] as num?)?.toInt() ?? _localFrames + 1;
      });
    } else if (type == 'status') {
      setState(() {
        _tcpState   = event['state']   as String? ?? '';
        _tcpMessage = event['message'] as String? ?? '';
        _tcpFrames  = (event['frames'] as num?)?.toInt() ?? 0;
      });
    } else if (type == 'capture_progress') {
      setState(() {
        _captureProgress = (event['current'] as num?)?.toInt();
        _captureMsg      = null;
      });
    } else if (type == 'capture_complete') {
      setState(() {
        _captureProgress     = null;
        _autoCaptureTriggered = true;
        _captureMsg          = 'Saved ${event['frames']} frames';
      });
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('Capture complete — ${event['path']}'),
          backgroundColor: Colors.green[800],
          duration: const Duration(seconds: 5),
        ));
      }
    } else if (type == 'auto_status') {
      setState(() {
        _distanceMm        = (event['mm'] as num?)?.toInt() ?? 0;
        _distanceStatus    = event['status'] as String? ?? '';
        _stabilityProgress = (event['stability'] as num?)?.toDouble() ?? 0.0;
      });
    }
  }

  Future<void> _connect() async {
    final ip   = _ipCtrl.text.trim();
    final port = int.tryParse(_portCtrl.text.trim()) ?? 9999;
    if (ip.isEmpty) {
      setState(() => _tcpMessage = 'Enter the laptop IP address');
      return;
    }
    setState(() {
      _tcpState   = 'connecting';
      _tcpMessage = 'Connecting…';
      _tcpFrames  = 0;
    });
    try {
      await _method.invokeMethod('startStreaming', {'ip': ip, 'port': port});
    } on PlatformException catch (e) {
      setState(() { _tcpState = 'error'; _tcpMessage = e.message ?? 'Error'; });
    }
  }

  Future<void> _disconnect() async {
    await _method.invokeMethod('stopStreaming');
    setState(() { _tcpState = 'idle'; _tcpMessage = 'Disconnected'; _tcpFrames = 0; });
  }

  Future<void> _triggerCapture() async {
    if (_captureProgress != null) return;
    await _method.invokeMethod('startBurstCapture');
  }

  Future<void> _captureRoi() async {
    setState(() { _roiSaving = true; _roiSaved = false; });
    try {
      await _method.invokeMethod('captureRoi');
      setState(() { _roiSaved = true; });
      Future.delayed(const Duration(seconds: 2), () {
        if (mounted) setState(() { _roiSaved = false; });
      });
    } on PlatformException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('ROI capture failed: ${e.message}'),
          backgroundColor: Colors.red[800],
        ));
      }
    } finally {
      setState(() { _roiSaving = false; });
    }
  }

  Future<void> _toggleAutoCapture() async {
    final next = !_autoCaptureEnabled;
    setState(() {
      _autoCaptureEnabled   = next;
      _autoCaptureTriggered = false;
      _stabilityProgress    = 0.0;
    });
    await _method.invokeMethod('toggleAutoCapture', next);
  }

  // ── Build ──────────────────────────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF0D1117),
      appBar: AppBar(
        backgroundColor: const Color(0xFF0D1117),
        elevation: 0,
        title: const Text(
          'EyeSense',
          style: TextStyle(
              color: Colors.cyanAccent,
              fontWeight: FontWeight.bold,
              letterSpacing: 2),
        ),
        actions: [
          IconButton(
            icon: Icon(
              _autoCaptureEnabled
                  ? Icons.motion_photos_auto
                  : Icons.motion_photos_auto_outlined,
              color: _autoCaptureEnabled
                  ? (_autoCaptureTriggered ? Colors.greenAccent : Colors.cyanAccent)
                  : Colors.white38,
            ),
            tooltip: _autoCaptureEnabled ? 'Auto Capture ON' : 'Auto Capture OFF',
            onPressed: _toggleAutoCapture,
          ),
          const SizedBox(width: 4),
        ],
        bottom: TabBar(
          controller: _tabCtrl,
          isScrollable: true,
          indicatorColor: Colors.cyanAccent,
          indicatorWeight: 2,
          labelColor: Colors.cyanAccent,
          unselectedLabelColor: Colors.white38,
          labelStyle: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
          tabs: const [
            Tab(text: 'Mozart'),
            Tab(text: 'Depth'),
            Tab(text: 'Confidence'),
            // Tab(text: 'Gradient'),
            // Tab(text: 'N2 Asym'),
            Tab(icon: Icon(Icons.center_focus_strong, size: 16), text: 'ROI'),
            Tab(icon: Icon(Icons.computer, size: 16), text: 'Laptop'),
          ],
        ),
      ),
      floatingActionButton: _tabCtrl.index < 3
          ? FloatingActionButton(
              onPressed: _captureProgress != null ? null : _triggerCapture,
              backgroundColor:
                  _captureProgress != null ? Colors.grey[800] : Colors.cyanAccent,
              foregroundColor: Colors.black,
              tooltip: 'Capture 30 frames',
              child: _captureProgress != null
                  ? Text('${_captureProgress}',
                      style: const TextStyle(
                          fontWeight: FontWeight.bold, color: Colors.white))
                  : const Icon(Icons.camera_alt),
            )
          : null,
      body: TabBarView(
        controller: _tabCtrl,
        physics: const NeverScrollableScrollPhysics(),
        children: [
          _buildLocalView('Mozart',     Colors.deepPurpleAccent),
          _buildLocalView('Depth Map',  Colors.blueAccent),
          _buildLocalView('Confidence', Colors.greenAccent),
          // _buildLocalView('Gradient',   Colors.orangeAccent),
          // _buildLocalView('N2 Asym',    Colors.pinkAccent),
          _buildRoiView(),
          _buildLaptopView(),
        ],
      ),
    );
  }

  // ── Local visualization tab ────────────────────────────────────────────────

  Widget _buildLocalView(String label, Color accent) {
    final distColor = switch (_distanceStatus) {
      'ok'        => Colors.green,
      'too_close' => Colors.red,
      'too_far'   => Colors.yellow,
      _           => Colors.white54,
    };
    final distLabel = switch (_distanceStatus) {
      'ok'        => 'Hold Still…',
      'too_close' => 'Too Close — Move Back',
      'too_far'   => 'Too Far — Move Closer',
      _           => '',
    };

    return Stack(
      fit: StackFit.expand,
      children: [
        // Frame image
        _frameData != null
            ? Image.memory(_frameData!, fit: BoxFit.contain, gaplessPlayback: true)
            : Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    CircularProgressIndicator(color: accent),
                    const SizedBox(height: 16),
                    Text('Waiting for $label…',
                        style: const TextStyle(color: Colors.white38, fontSize: 13)),
                  ],
                ),
              ),

        // Oval face guide (auto-capture mode)
        if (_autoCaptureEnabled)
          CustomPaint(
            painter: _OvalGuidePainter(color: distColor.withAlpha(128)),
          ),

        // Stability countdown ring
        if (_autoCaptureEnabled && _stabilityProgress > 0 && !_autoCaptureTriggered)
          Center(
            child: SizedBox(
              width: 120,
              height: 120,
              child: CircularProgressIndicator(
                value: _stabilityProgress,
                strokeWidth: 6,
                color: Colors.greenAccent,
                backgroundColor: Colors.white12,
              ),
            ),
          ),

        // Distance guidance (auto-capture mode)
        if (_autoCaptureEnabled && _distanceMm > 0)
          Positioned(
            bottom: 90,
            left: 0,
            right: 0,
            child: Column(
              children: [
                Text(
                  '$_distanceMm mm',
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 36,
                    fontWeight: FontWeight.bold,
                    fontFamily: 'monospace',
                    shadows: [Shadow(color: Colors.black, blurRadius: 8)],
                  ),
                ),
                if (distLabel.isNotEmpty)
                  Text(
                    distLabel,
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      color: distColor,
                      fontSize: 18,
                      fontWeight: FontWeight.w600,
                      shadows: const [Shadow(color: Colors.black, blurRadius: 6)],
                    ),
                  ),
              ],
            ),
          ),

        // Capture progress bar + label
        if (_captureProgress != null)
          Positioned(
            top: 0,
            left: 0,
            right: 0,
            child: Column(
              children: [
                LinearProgressIndicator(
                  value: _captureProgress! / 30.0,
                  color: Colors.cyanAccent,
                  backgroundColor: Colors.black45,
                  minHeight: 4,
                ),
                Container(
                  color: Colors.black54,
                  padding: const EdgeInsets.symmetric(vertical: 3),
                  child: Text(
                    'Capturing…  ${_captureProgress!}/30',
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                        color: Colors.cyanAccent,
                        fontSize: 12,
                        fontFamily: 'monospace'),
                  ),
                ),
              ],
            ),
          ),

        // Capture complete banner
        if (_captureMsg != null && _captureProgress == null)
          Positioned(
            top: 8,
            left: 0,
            right: 0,
            child: Center(
              child: Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
                decoration: BoxDecoration(
                  color: Colors.green[900]!.withAlpha(220),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Text(_captureMsg!,
                    style: const TextStyle(
                        color: Colors.greenAccent, fontSize: 13)),
              ),
            ),
          ),

        // Frame counter badge
        Positioned(
          top: 8,
          right: 8,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
            decoration: BoxDecoration(
              color: Colors.black54,
              borderRadius: BorderRadius.circular(6),
            ),
            child: Text(
              '$label  ·  $_localFrames f',
              style:
                  TextStyle(color: accent, fontSize: 11, fontFamily: 'monospace'),
            ),
          ),
        ),
      ],
    );
  }

  // ── ROI tab ───────────────────────────────────────────────────────────────

  Widget _buildRoiView() {
    return Stack(
      fit: StackFit.expand,
      children: [
        _frameData != null
            ? Image.memory(_frameData!, fit: BoxFit.fill, gaplessPlayback: true)
            : const Center(
                child: CircularProgressIndicator(color: Colors.cyanAccent)),
        // Cyan oval overlay on right eye region (fixed position)
        const CustomPaint(painter: _RoiOvalPainter()),
        // Capture button
        Positioned(
          bottom: 32,
          left: 0,
          right: 0,
          child: Center(
            child: ElevatedButton.icon(
              onPressed: _roiSaving ? null : _captureRoi,
              icon: Icon(_roiSaved ? Icons.check : Icons.save_alt),
              label: Text(_roiSaved ? 'Saved!' : 'Capture ROI'),
              style: ElevatedButton.styleFrom(
                backgroundColor:
                    _roiSaved ? Colors.green[700] : Colors.cyanAccent,
                foregroundColor: Colors.black,
                padding:
                    const EdgeInsets.symmetric(horizontal: 28, vertical: 14),
                textStyle: const TextStyle(
                    fontSize: 15, fontWeight: FontWeight.bold),
              ),
            ),
          ),
        ),
      ],
    );
  }

  // ── Laptop (TCP) tab ───────────────────────────────────────────────────────

  Widget _buildLaptopView() {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const SizedBox(height: 8),
          const Text(
            'Laptop Server',
            style: TextStyle(
                fontSize: 20,
                fontWeight: FontWeight.bold,
                color: Colors.cyanAccent,
                letterSpacing: 1.5),
          ),
          const SizedBox(height: 4),
          const Text(
            'Raw ToF  →  TCP  →  Mozart N1/N2 + Autoencoder',
            style: TextStyle(fontSize: 12, color: Colors.white38),
          ),
          const SizedBox(height: 32),

          // IP field
          _label('Laptop IP Address'),
          const SizedBox(height: 6),
          TextField(
            controller: _ipCtrl,
            enabled: !_isTcpActive,
            keyboardType: TextInputType.number,
            style: const TextStyle(fontFamily: 'monospace', fontSize: 16),
            decoration: _inputDeco('e.g. 192.168.1.100'),
          ),
          const SizedBox(height: 16),

          // Port field
          _label('Port'),
          const SizedBox(height: 6),
          TextField(
            controller: _portCtrl,
            enabled: !_isTcpActive,
            keyboardType: TextInputType.number,
            style: const TextStyle(fontFamily: 'monospace', fontSize: 16),
            decoration: _inputDeco('9999'),
          ),
          const SizedBox(height: 28),

          // Connect / Disconnect
          SizedBox(
            height: 52,
            child: ElevatedButton(
              style: ElevatedButton.styleFrom(
                backgroundColor:
                    _isTcpActive ? Colors.redAccent : Colors.cyanAccent,
                foregroundColor: Colors.black,
                shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12)),
              ),
              onPressed: _isTcpActive ? _disconnect : _connect,
              child: Text(
                _isTcpActive ? 'Disconnect' : 'Connect & Stream',
                style: const TextStyle(
                    fontSize: 16, fontWeight: FontWeight.bold),
              ),
            ),
          ),

          const SizedBox(height: 28),

          // Status card
          _statusCard(),

          const SizedBox(height: 24),

          // Protocol reference
          _protocolCard(),

          const SizedBox(height: 12),
          const Text(
            'Run  laptop_server.py  on your laptop',
            textAlign: TextAlign.center,
            style: TextStyle(color: Colors.white24, fontSize: 11),
          ),
          const SizedBox(height: 16),
        ],
      ),
    );
  }

  Widget _statusCard() {
    final stateColor = _tcpStateColor;
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: const Color(0xFF161B22),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: stateColor.withOpacity(0.35)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            Container(
                width: 9,
                height: 9,
                decoration:
                    BoxDecoration(color: stateColor, shape: BoxShape.circle)),
            const SizedBox(width: 8),
            Text(
              _tcpState.toUpperCase(),
              style: TextStyle(
                  color: stateColor,
                  fontWeight: FontWeight.bold,
                  fontSize: 13,
                  letterSpacing: 1.2),
            ),
          ]),
          const SizedBox(height: 8),
          Text(_tcpMessage,
              style: const TextStyle(color: Colors.white70, fontSize: 13)),
          if (_tcpFrames > 0) ...[
            const SizedBox(height: 14),
            Text(
              '$_tcpFrames frames',
              style: const TextStyle(
                  color: Colors.cyanAccent,
                  fontSize: 30,
                  fontWeight: FontWeight.bold,
                  fontFamily: 'monospace'),
            ),
            Text(
              '${(_tcpFrames / 30).toStringAsFixed(1)} sec',
              style: const TextStyle(
                  color: Colors.white38,
                  fontSize: 12,
                  fontFamily: 'monospace'),
            ),
          ],
        ],
      ),
    );
  }

  Widget _protocolCard() {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: const Color(0xFF161B22),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: Colors.white12),
      ),
      child: const Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Wire protocol  (big-endian TCP)',
              style: TextStyle(
                  color: Colors.white54,
                  fontSize: 10,
                  fontWeight: FontWeight.bold,
                  letterSpacing: 0.8)),
          SizedBox(height: 8),
          Text(
            '[4]  magic    0x4E314E32  "N1N2"\n'
            '[4]  frame_num  uint32\n'
            '[4]  width      uint32\n'
            '[4]  height     uint32\n'
            '[4]  max_depth  uint32 mm\n'
            '[W×H×2]  depth  uint16 BE mm\n'
            '[W×H×1]  conf   uint8  0-255',
            style: TextStyle(
                color: Colors.white30,
                fontSize: 10,
                fontFamily: 'monospace',
                height: 1.6),
          ),
        ],
      ),
    );
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  Color get _tcpStateColor {
    switch (_tcpState) {
      case 'streaming':  return Colors.cyanAccent;
      case 'connected':  return Colors.greenAccent;
      case 'connecting': return Colors.orangeAccent;
      case 'error':      return Colors.redAccent;
      default:           return Colors.white38;
    }
  }

  Widget _label(String text) => Text(text,
      style: const TextStyle(
          color: Colors.white54, fontSize: 11, letterSpacing: 0.7));

  InputDecoration _inputDeco(String hint) => InputDecoration(
        hintText: hint,
        hintStyle: const TextStyle(color: Colors.white24),
        filled: true,
        fillColor: const Color(0xFF161B22),
        border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(8),
            borderSide: const BorderSide(color: Colors.white12)),
        enabledBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(8),
            borderSide: const BorderSide(color: Colors.white12)),
        focusedBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(8),
            borderSide:
                const BorderSide(color: Colors.cyanAccent, width: 1.5)),
        disabledBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(8),
            borderSide: const BorderSide(color: Colors.white12)),
        contentPadding:
            const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
      );
}

// Fixed cyan oval for right-eye ROI (subject's right eye = left side of portrait display)
// Adjust fractions if the oval needs repositioning for your device.
class _RoiOvalPainter extends CustomPainter {
  const _RoiOvalPainter();

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = Colors.cyan.withAlpha(180)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2.5;
    final cx = size.width  * 0.35;
    final cy = size.height * 0.35;
    final rx = size.width  * 0.06;
    final ry = size.height * 0.05;
    canvas.drawOval(
      Rect.fromCenter(center: Offset(cx, cy), width: rx * 2, height: ry * 2),
      paint,
    );
  }

  @override
  bool shouldRepaint(_RoiOvalPainter _) => false;
}

class _OvalGuidePainter extends CustomPainter {
  final Color color;
  const _OvalGuidePainter({required this.color});

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = 3;
    final cx = size.width / 2;
    final cy = size.height / 2;
    final rx = size.width * 0.35;
    final ry = size.height * 0.45;
    canvas.drawOval(
      Rect.fromCenter(
          center: Offset(cx, cy), width: rx * 2, height: ry * 2),
      paint,
    );
  }

  @override
  bool shouldRepaint(_OvalGuidePainter old) => old.color != color;
}
