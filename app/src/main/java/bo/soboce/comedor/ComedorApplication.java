package bo.soboce.comedor;

import android.app.Application;
import bo.colbert.licensing.LicenseClient;

public final class ComedorApplication extends Application implements LicenseClient.Provider {
    @Override public LicenseClient currentLicense(){
        try{return Companies.enabled(this)?Companies.license(this,Companies.current(this)):null;}
        catch(Exception e){throw new IllegalStateException("No se pudo abrir el permiso de la empresa.",e);}
    }
}
