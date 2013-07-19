/**
 * Interface to the errlog service, see http://errorlog.co
 * @author real.sergeych@gmail.com
 */
package co.errlog;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Base64;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Interface to the errlog service, see http://errorlog.co
 *
 * The package is fully functional. The uncaught exception handler though is in question, the transport
 * needs to be tested. Nested exceptions are supported.
 *
 * How to use:
 *
 * Be sure to have credentials from http://errorlog.co
 *
 * Call Errlog.initialize as early as possible. You could call it as many times as you want.
 *
 * Call Errlog.d, Errlog.w and Errlog.e to log instead of Log.d, Log.e and Log.w.
 *
 * Use one of Errlog.trace, Errlog.warning and Errlog.error to report to the service. The report will include latest log
 * lines, stack, if Throwable object was specified, and hardare/software/application information.
 *
 * Errlog installs also unhandled exceptions handler.
 *
 * @author real.sergeych@gmail.com
 */
public class Errlog {

    static private String accountId, appName;
    static private byte[] accountSecret;

    static final int ERROR = 100;
    static final int WARNING = 50;
    static final int NOT_FOUND = 49;
    static final int TRACE = 1;
    static final int STAT_DATA = 1001;

    private static SharedPreferences prefs;
    private static String instanceId;

    private static PackageInfo pInfo;

    private static final String TAG = "errlog";

    /**
     * Initialize errlog.co service. Call it as early as possible in yout code,
     * prior to use any other errlog facilities. See http://errlog.co for
     * configuration parameters. It also calls Errlog.startSession() so you
     * need not to call it.
     * <p/>
     * Please note that only the first invocation will actually set configuration parameters,
     * all subsequent calls will only call startSession().
     * <p/>
     * It also installs uncaught exception handler in the chain (calls existing one). Death reports
     * are being sent in the separate thread, need to test how good is it.
     * <p/>
     * This version uses Android SharedPreferences storage. There is also pure java version under way
     * to use in other java platforms, but there is a problem to store persistent parameters in the
     * platform-independent way effectively.
     */
    static public void initialize(Context context, String accId,
                                  String accKey, String name) {
        if (prefs == null) {
            try {
                pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "Can't find package info");
                e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            }
            prefs = context.getSharedPreferences("_errlog", Activity.MODE_PRIVATE);
            accountId = accId;
            accountSecret = Base64.decode(accKey, Base64.DEFAULT);
            appName = name;

            final Thread.UncaughtExceptionHandler oldHandler = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread thread, Throwable throwable) {
                    error(TAG, "Uncaught", throwable, "thread", thread.toString());
                    oldHandler.uncaughtException(thread, throwable);
                }
            });

            initializeStatistics();
        }
        startSession();

//        RuntimeException x = new RuntimeException("outer test exception");
//        RuntimeException i1 = new RuntimeException("inner1 test exception");
//        RuntimeException i2 = new RuntimeException("inner2 test exception");
//        x.initCause(i1);
//        i1.initCause(i2);
//        error("errlog", "test inner exception", x);
    }

    private static ArrayList<JSONArray> logBuffer = new ArrayList<JSONArray>();

    /**
     * Log a debug string. Analog of Log.d, but these log messages are buffered and
     * last are sent with warnings and errors to errlog service.
     *
     * @param tag
     * @param message
     * @param params  if present, then message is treated as String.format format
     */
    public static void d(String tag, String message, Object... params) {
        if (params.length > 0)
            message = String.format(message, params);
        Log.d(tag, message);
        collectLog(1, tag, message);
    }

    /**
     * Log a warning string. Analog of Log.d, but these log messages are buffered and
     * last are sent with warnings and errors to errlog service.
     *
     * @param tag
     * @param message
     * @param params  if present, then message is treated as String.format format
     */
    public static void w(String tag, String message, Object... params) {
        if (params.length > 0)
            message = String.format(message, params);
        Log.w(tag, message);
        collectLog(2, tag, message);
    }

    /**
     * Log an error string. Analog of Log.d, but these log messages are buffered and
     * last are sent with warnings and errors to errlog service.
     *
     * @param tag
     * @param message
     * @param params  if present, then message is treated as String.format format
     */
    public static void e(String tag, String message, Object... params) {
        if (params.length > 0)
            message = String.format(message, params);
        Log.e(tag, message);
        collectLog(3, tag, message);
    }

    /**
     * Log an error string. Analog of Log.d, but these log messages are buffered and
     * last are sent with warnings and errors to errlog service.
     *
     * @param tag
     * @param message
     * @param t
     * @param params  if present, then message is treated as String.format format
     */
    public static void e(String tag, String message, Throwable t, Object... params) {
        if (params.length > 0)
            message = t.toString() + ": " + String.format(message, params);
        Log.e(tag, message, t);
        collectLog(3, tag, message);
    }

    private static void collectLog(int level, String tag, String message) {
        if (logBuffer.size() > 99)
            logBuffer.remove(0);
        JSONArray data = new JSONArray();
        data.put(level);
        data.put(new Date().getTime() / 1000);
        data.put(null);
        data.put(getTagTextString(tag, message));
        logBuffer.add(data);
    }

    private static String getTagTextString(String tag, String message) {
        String s;
        if (tag != null && !tag.isEmpty())
            s = String.format("%s: %s", tag, message);
        else
            s = message;
        return s;
    }

    private static void initializeStatistics() {
        instanceId = prefs.getString("instanceId", null);
        if (instanceId == null) {
            instanceId = randomId(32);
            prefs.edit().putString("instanceId", instanceId).commit();
            Log.i("behlog", "Created new instance id");
        } else {
            Log.i("behlog", "Known instance: " + instanceId);
        }
    }

    private static long sessionStartedAt = 0;

    /**
     * Call it early when user is presented with the first activity. No need to call right after
     * initialize() - it calls it already.
     */
    public static void startSession() {
        if (sessionStartedAt == 0) {
            Log.d("behlog", "Starting new session");
            sessionStartedAt = new Date().getTime();
            reportStats("session_started");
        } else {
            Log.d("behlog", "Session is already started, ignoring");
        }
    }

    /**
     * Call it when user uses your UI no more to report session duration and other stats.
     * After, you might need to call startSession() again.
     */
    public static void stopSession() {
        if (sessionStartedAt == 0) {
            Log.e("behlog", "stopSession called when no open session is known");
        } else {
            long duration = (new Date().getTime() - sessionStartedAt) / 1000;
            reportStats("session_finished", "duration", duration);
            sessionStartedAt = 0;
            Log.d("behlog", "Session finished, reports scheduled");
        }
    }

    /**
     * Send trace report to errlog service. Data may include any extra information that will be added to the report,
     * as "key, value" sequence, e.g. trace("other","life is great", "how_great", 122).
     * Keys must be strings. Values must be of types acceptable for JSONObject.
     *
     * @param tag
     * @param message
     * @param data
     */
    public static void trace(String tag, String message, Object... data) {
        Log.d(tag, message);
        report(TRACE, tag, message, null, data);
    }

    /**
     * Send warning report to errlog service.
     *
     * @param tag
     * @param message
     * @param data
     * @see Errlog.trace();
     */
    public static void warning(String tag, String message, Object... data) {
        Log.w(tag, message);
        report(WARNING, tag, message, null, data);
    }

    /**
     * Send warning report to errlog service.
     *
     * @param tag
     * @param message
     * @param t
     * @param data
     * @see Errlog.trace();
     */
    public static void error(String tag, String message, Throwable t, Object... data) {
        e(tag, message, t);
        report(ERROR, tag, message, t, data);
    }

    /**
     * Report errlog error using current log and arbitrary extra parameters.
     *
     * @param tag
     * @param message
     * @param data
     * @see Errlog.trace();
     */
    public static void error(String tag, String message, Object... data) {
        e(tag, message);
        report(ERROR, tag, message, null, data);
    }

    static void report(int code, String tag, String text, Throwable t, Object[] args) {
        try {
            HashMap<String, Object> data = getStringObjectHashMap("component", tag, args);
            if (!logBuffer.isEmpty()) {
                data.put("log", new JSONArray(logBuffer));
            }
            if (t != null) {
                data.put("exception_class", t.getClass().getName());
                text += ": " + t.toString();
                JSONArray stack = new JSONArray();

                Throwable ex = t;
                while (ex != null) {
                    for (StackTraceElement st : ex.getStackTrace()) {
                        stack.put(st.toString());
                    }
                    ex = ex.getCause();
                    if(ex != null) {
                        stack.put("--- Caused by: " + ex.getMessage() );
                    }
                }
                data.put("stack", stack);
            }
            report(code, text, data);
        } catch (Throwable x) {
            Log.e(TAG, "Cannot prepare data for report", x);
        }
    }

    /**
     * Send an arbitrary Errlog report. Usually this method is not used by end
     * user. This method may be used to send any data package: error, trace,
     * statistics, whatever. Intended for package use.
     *
     * @param code message type/severity. See type constants.
     * @param text message text. can be null for special data types
     * @param data optionally data map. Can be null.
     */
    static void report(int code, String text, Map<String, Object> data) {
        if (data == null)
            data = new HashMap<String, Object>();

        data.put("severity", code);
        if (text != null)
            data.put("text", text);
        data.put("platform", "android");
        data.put("application", appName);
        data.put("instance_id", instanceId);
        data.put("hw_model", android.os.Build.MODEL);
        data.put("hw_manufacturer", android.os.Build.MANUFACTURER);
        data.put("os_version", android.os.Build.VERSION.RELEASE);
        data.put("version", pInfo.versionName);

        Log.i(TAG, "Report:" + data);

        try {
            final byte[] packed = pack(data);
            new Thread(new Runnable() {
                // @Override
                public void run() {
                    try {
                        send(packed);
                    } catch (IOException e) {
                        Log.e(TAG, "Failed to send report", e);
                        e.printStackTrace();
                    }
                }
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "Failed to prepare report", e);
            e.printStackTrace();
        }
    }

    /**
     * Send an arbitrary statistics report.
     *
     * @param type required. statistics package type, e.g. start_session, event,
     *             etc.
     * @param args optional. key, value pairs to include in report. Keys are
     *             converted to strings, but values are left as is. Should be 0
     *             or even number of arguments.
     */
    public static void reportStats(String type, Object... args) {
        report(STAT_DATA, null, getStringObjectHashMap("stype", type, args));
    }

    /**
     * Construct parameters hash using one required parameter and arbitrary parameters
     * presented in the array "key", "value"... to be used with varargs
     *
     * @param name  required param's name
     * @param value required param's value
     * @param args  array of additional parameters: key, value sequence.
     * @return the resulting hash
     */
    static HashMap<String, Object> getStringObjectHashMap(String name, String value, Object[] args) {
        if ((args.length & 1) == 1)
            throw new IllegalArgumentException(
                    "Errlog accepts only even number of optional args");
        if (value.isEmpty() || name.isEmpty())
            throw new IllegalArgumentException("Errlog required parameter is not set");

        HashMap<String, Object> data = new HashMap<String, Object>();
        data.put(name, value);
        for (int i = 0; i < args.length; i += 2) {
            data.put(args[i].toString(), args[i + 1]);
        }
        return data;
    }

    /**
     * Report arbitrary statistics event (say, user is logged in).
     *
     * @param eventName
     * @param args      optinoal. arbitrary data to be collected with this event.
     */
    public static void reportEvent(String eventName, Object... args) {
        report(STAT_DATA, null, getStringObjectHashMap("event_name", eventName, args));
    }

    private static SecureRandom random = new SecureRandom();

    /**
     * Get the more or less secure random alnum string.
     *
     * @param length in characters
     * @return The pseudo-random string computed using cryptographic RNG
     */
    public static String randomId(int length) {
        return new BigInteger(length * 5, random).toString(32);
    }

    private static final Charset UTF8_CHARSET = Charset.forName("UTF-8");

    private static byte[] pack(Map<String, Object> map) throws IOException,
            NoSuchAlgorithmException {
        byte[] payload = new JSONObject(map).toString().getBytes(UTF8_CHARSET);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(1); // Type 1: JSON, not encrypted, deflated

        // Deflated payload
        ByteArrayOutputStream b1 = new ByteArrayOutputStream();
        GZIPOutputStream dos = new GZIPOutputStream(b1);
        dos.write(payload);
        dos.close();
        byte[] compressed = b1.toByteArray();

        bos.write(compressed);

        // Digest
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(compressed);
        md.update(accountSecret);
        bos.write(md.digest());

        bos.close();
        return bos.toByteArray();
    }

    private static String boundary;

    static {
        boundary = randomId(61);
    }

    private static void send(byte[] packed) throws IOException {
        int serverResponseCode;

        HttpURLConnection conn = null;
        DataOutputStream dos = null;
        String lineEnd = "\r\n";
        String twoHyphens = "--";
//        URL url = new URL("http://192.168.1.3:8080/reports/log?app_id=" + accountId);
        URL url = new URL("http://errorlog.co/reports/log?app_id=" + accountId);
        conn = (HttpURLConnection) url.openConnection();

        conn.setDoInput(true); // Allow Inputs
        conn.setDoOutput(true); // Allow Outputs
        conn.setUseCaches(false);

        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data;boundary="
                + boundary);
        // conn.setRequestProperty("uploaded_file", "file");

        dos = new DataOutputStream(conn.getOutputStream());

        dos.writeBytes(twoHyphens + boundary + lineEnd);
        dos.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"repport.bin\""
                + lineEnd);
        dos.writeBytes(lineEnd);

        dos.write(packed);

        // send multipart form data necesssary after file data...
        dos.writeBytes(lineEnd);
        dos.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd);
        // dos.flush();

        // Responses from the server (code and message)
        serverResponseCode = conn.getResponseCode();
        String serverResponseMessage = conn.getResponseMessage();

        // Log.i(TAG, "upload url: " + url);
        Log.d(TAG, "HTTP Response is : " + serverResponseMessage + ": "
                + serverResponseCode);
        if (serverResponseCode == 200) {
            // Life is great, so?
        }
        // close the streams
        dos.close();
    }

}
