import SwiftUI
import AVFoundation

struct CameraView:UIViewControllerRepresentable {
 var active:Bool;var receive:(String)->Void;var failure:(String)->Void
 func makeUIViewController(context:Context)->QRController{let c=QRController();c.receive=receive;c.failure=failure;c.enabled=active;return c}
 func updateUIViewController(_ c:QRController,context:Context){c.receive=receive;c.failure=failure;c.enabled=active;c.update()}
 static func dismantleUIViewController(_ c:QRController,coordinator:()){c.stop()}
}
final class QRController:UIViewController,AVCaptureMetadataOutputObjectsDelegate {
 let session=AVCaptureSession(),queue=DispatchQueue(label:"com.colbert.camera");var preview:AVCaptureVideoPreviewLayer?
 var receive:((String)->Void)?,failure:((String)->Void)?,enabled=true,ready=false,last="";var lastTime=Date.distantPast
 override func viewDidLoad(){super.viewDidLoad();view.backgroundColor = .black
  switch AVCaptureDevice.authorizationStatus(for:.video){case .authorized:setup();case .notDetermined:AVCaptureDevice.requestAccess(for:.video){ok in DispatchQueue.main.async{if ok{self.setup()}else{self.failure?("Permite la cámara en Ajustes para escanear los QR.")}}};default:failure?("Permite la cámara en Ajustes para escanear los QR.")}
 }
 func setup(){guard !ready else{return};guard let device=AVCaptureDevice.default(for:.video),let input=try? AVCaptureDeviceInput(device:device)else{failure?("La cámara no está disponible en este dispositivo.");return};let output=AVCaptureMetadataOutput();session.beginConfiguration();guard session.canAddInput(input),session.canAddOutput(output)else{session.commitConfiguration();failure?("No se pudo iniciar el escáner.");return};session.addInput(input);session.addOutput(output);output.setMetadataObjectsDelegate(self,queue:.main);output.metadataObjectTypes=[.qr];session.commitConfiguration();let layer=AVCaptureVideoPreviewLayer(session:session);layer.videoGravity = .resizeAspectFill;view.layer.addSublayer(layer);preview=layer;ready=true;view.setNeedsLayout();update()}
 override func viewDidLayoutSubviews(){super.viewDidLayoutSubviews();preview?.frame=view.bounds;if preview?.connection?.isVideoOrientationSupported==true{preview?.connection?.videoOrientation = .portrait}}
 func update(){guard ready else{return};let start=enabled;queue.async{if start && !self.session.isRunning{self.session.startRunning()}else if !start && self.session.isRunning{self.session.stopRunning()}}}
 func stop(){enabled=false;update()}
 func metadataOutput(_ output:AVCaptureMetadataOutput,didOutput metadataObjects:[AVMetadataObject],from connection:AVCaptureConnection){guard enabled,let raw=(metadataObjects.first as? AVMetadataMachineReadableCodeObject)?.stringValue,raw != last || Date().timeIntervalSince(lastTime)>3 else{return};last=raw;lastTime=Date();receive?(raw)}
}
