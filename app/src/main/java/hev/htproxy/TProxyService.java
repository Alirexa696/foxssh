package hev.htproxy;

/**
 * اتصال JNI به hev-socks5-tunnel. نام پکیج/کلاس باید دقیقاً همین باشد
 * (در کتابخانهٔ native ثبت شده است).
 */
public class TProxyService {
    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    public native boolean TProxyStartService(String configPath, int fd);

    public native boolean TProxyStopService();

    public native boolean TProxyIsRunning();

    /** [tx بسته، tx بایت، rx بسته، rx بایت] */
    public native long[] TProxyGetStats();
}
