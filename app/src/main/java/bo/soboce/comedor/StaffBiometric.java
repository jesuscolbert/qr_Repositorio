package bo.soboce.comedor;

import android.content.Context;
import androidx.biometric.BiometricManager;

/** The same strong authenticators are checked and requested, on Android 8 and later. */
final class StaffBiometric {
 static final int AUTHENTICATORS=BiometricManager.Authenticators.BIOMETRIC_STRONG;
 static int status(Context context){try{return BiometricManager.from(context).canAuthenticate(AUTHENTICATORS);}catch(SecurityException unavailable){return BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE;}}
 static String problem(int status){switch(status){
  case BiometricManager.BIOMETRIC_SUCCESS:return "";
  case BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED:return "Falta registrar una huella en los ajustes de seguridad del teléfono.";
  case BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE:return "Android no encuentra un lector de huella o biometría segura compatible en este teléfono.";
  case BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE:return "El lector está temporalmente ocupado o no disponible. Desbloquea el teléfono y vuelve a comprobar.";
  case BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED:return "Android requiere una actualización de seguridad antes de permitir esta biometría.";
  default:return "Android no pudo confirmar la disponibilidad de la biometría. Revisa los ajustes del teléfono y vuelve a comprobar.";
 }}
}
