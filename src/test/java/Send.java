import java.net.HttpURLConnection;
import java.net.URL;

public class Send {
    public static void main(final String[] args) throws Exception {
        final HttpURLConnection conn = (HttpURLConnection) new URL("http://localhost:25570/upload").openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);

    }
}
