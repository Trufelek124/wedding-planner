import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The container's health check: asks the application whether it is ready and exits with 0 or 1.
 *
 * <p>The runtime image has Java and little else, on purpose: no curl, no wget. So the question is
 * asked in Java. This file is not part of the application; the Dockerfile compiles it on its own.
 */
public final class Health {

    private Health() {}

    public static void main(String[] args) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080/actuator/health"))
                    .timeout(Duration.ofSeconds(4))
                    .build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status != 200) {
                System.err.println("The application answered " + status);
            }
            System.exit(status == 200 ? 0 : 1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.exit(1);
        } catch (Exception e) {
            System.err.println("The application did not answer: " + e);
            System.exit(1);
        }
    }
}
