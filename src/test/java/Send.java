import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;

public class Send {
    public static void main(String[] args) throws Exception {
        final HttpURLConnection conn = (HttpURLConnection) new URL("http://localhost:25570/upload").openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);

    }
}
