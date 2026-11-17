package bo.soboce.comedor;

import android.app.Application;
import bo.colbert.licensing.LicenseClient;

public final class ComedorApplication extends Application implements LicenseClient.Provider {
    @Override public void onCreate(){super.onCreate();registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks(){
        public void onActivityStarted(android.app.Activity a){StaffAccess.started();}
        public void onActivityStopped(android.app.Activity a){StaffAccess.stopped();}
        public void onActivityCreated(android.app.Activity a,android.os.Bundle b){}
        public void onActivityResumed(android.app.Activity a){}
        public void onActivityPaused(android.app.Activity a){}
        public void onActivitySaveInstanceState(android.app.Activity a,android.os.Bundle b){}
        public void onActivityDestroyed(android.app.Activity a){}
    });}
    @Override public LicenseClient currentLicense(){
        try{return Companies.enabled(this)?Companies.license(this,Companies.current(this)):null;}
        catch(Exception e){throw new IllegalStateException("No se pudo abrir el permiso de la empresa.",e);}
    }
}
