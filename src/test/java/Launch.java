import org.watermedia.WaterMedia;

public class Launch {
    public static void main(String[] args) {
        WaterMedia.start("LAUNCHER", null, null, false);
        while (!Thread.currentThread().isInterrupted()) {

        }
    }


}
