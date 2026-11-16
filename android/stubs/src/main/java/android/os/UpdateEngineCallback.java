package android.os;

/** Stub of the framework's @SystemApi android.os.UpdateEngineCallback. */
public abstract class UpdateEngineCallback {
    public abstract void onStatusUpdate(int status, float percent);
    public abstract void onPayloadApplicationComplete(int errorCode);
}
