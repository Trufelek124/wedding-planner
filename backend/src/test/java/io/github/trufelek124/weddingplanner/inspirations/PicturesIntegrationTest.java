package io.github.trufelek124.weddingplanner.inspirations;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestHttp.Part;
import io.github.trufelek124.weddingplanner.TestWedding;
import io.github.trufelek124.weddingplanner.inspirations.internal.TestPictures;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/** The pictures of inspirations: kept on the server's disk and handed out to the two owners only. */
class PicturesIntegrationTest extends PostgresIntegrationTest {

    private static final Path STORED = MEDIA.resolve("inspirations");

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    TestHttp http;
    String anna;
    String piotr;

    @BeforeEach
    void aFreshWedding() throws Exception {
        TestWedding wedding = TestWedding.fresh(jdbc, port);
        http = wedding.http;
        anna = wedding.anna;
        piotr = wedding.piotr;
    }

    // ------------------------------------------------------------ keeping

    @Test
    void severalPicturesAreKeptInTheOrderSentWithTheirKindAndSize() throws Exception {
        String idea = inspiration(null);
        byte[] photo = TestPictures.jpeg(1600, 1200);
        byte[] drawing = TestPictures.png(300, 200);

        HttpResponse<String> response = http.postFilesAs(anna, pictures(idea), List.of(
                new Part("arch.jpg", "image/jpeg", photo), new Part("sketch.png", "image/png", drawing)));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        List<Map<String, Object>> kept = picturesOf(TestHttp.json(response));
        assertThat(kept).hasSize(2);
        assertThat(kept.get(0)).containsEntry("contentType", "image/jpeg").containsEntry("width", 1600).containsEntry("height", 1200);
        assertThat(kept.get(1)).containsEntry("contentType", "image/png").containsEntry("width", 300).containsEntry("height", 200);
        assertThat(kept.get(0)).containsOnlyKeys("id", "contentType", "width", "height");
        // On the disk: the picture as it came, and a smaller version, both named after the id alone.
        String first = (String) kept.get(0).get("id");
        assertThat(Files.readAllBytes(STORED.resolve(first))).isEqualTo(photo);
        assertThat(STORED.resolve(first + ".thumb")).exists();
        // The other owner sees the same pictures on the same inspiration.
        assertThat(picturesOf(TestHttp.json(http.getAs(piotr, "/api/inspirations/" + idea)))).isEqualTo(kept);
        assertThat(picturesOf(TestHttp.jsonList(http.getAs(piotr, "/api/inspirations")).get(0))).isEqualTo(kept);
    }

    @Test
    void aPictureCanBeAttachedLaterToALinkThatGaveNone() throws Exception {
        String idea = inspiration("https://www.instagram.com/p/Cabc123/");
        assertThat(picturesOf(TestHttp.json(http.getAs(anna, "/api/inspirations/" + idea)))).isEmpty();

        HttpResponse<String> response = http.postFilesAs(piotr, pictures(idea), List.of(
                new Part("screenshot.png", "image/png", TestPictures.png(400, 800))));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(TestHttp.json(response)).containsEntry("source", "INSTAGRAM").containsEntry("url", "https://www.instagram.com/p/Cabc123/");
        assertThat(picturesOf(TestHttp.json(response))).hasSize(1);
        // More can follow, after the ones already there.
        String firstId = (String) picturesOf(TestHttp.json(response)).get(0).get("id");
        HttpResponse<String> more = http.postFilesAs(anna, pictures(idea), List.of(new Part("b.jpg", "image/jpeg", TestPictures.jpeg(64, 64))));
        assertThat(picturesOf(TestHttp.json(more))).hasSize(2);
        assertThat(picturesOf(TestHttp.json(more)).get(0)).containsEntry("id", firstId);
    }

    @Test
    void aPhonePhotoIsReportedTheRightWayUp() throws Exception {
        String idea = inspiration(null);

        HttpResponse<String> response = http.postFilesAs(anna, pictures(idea), List.of(
                new Part("IMG_0001.jpg", "image/jpeg", TestPictures.jpegWithOrientation(800, 400, 6))));

        Map<String, Object> kept = picturesOf(TestHttp.json(response)).get(0);
        assertThat(kept).containsEntry("width", 400).containsEntry("height", 800);
        BufferedImage smaller = TestPictures.read(http.getBytesAs(anna, pictures(idea) + "/" + kept.get("id") + "?size=THUMBNAIL").body());
        assertThat(smaller.getWidth()).isLessThan(smaller.getHeight());
    }

    // ------------------------------------------------------------ handing out

    @Test
    void aPictureIsServedAsItCameAndAsASmallerVersion() throws Exception {
        String idea = inspiration(null);
        byte[] photo = TestPictures.jpeg(1600, 1200);
        String picture = uploaded(idea, new Part("arch.jpg", "image/jpeg", photo));

        HttpResponse<byte[]> full = http.getBytesAs(anna, pictures(idea) + "/" + picture);
        HttpResponse<byte[]> smaller = http.getBytesAs(piotr, pictures(idea) + "/" + picture + "?size=THUMBNAIL");

        assertThat(full.statusCode()).isEqualTo(200);
        assertThat(full.body()).isEqualTo(photo);
        assertThat(full.headers().firstValue("Content-Type")).contains("image/jpeg");
        assertThat(smaller.statusCode()).isEqualTo(200);
        assertThat(smaller.headers().firstValue("Content-Type")).contains("image/jpeg");
        BufferedImage thumbnail = TestPictures.read(smaller.body());
        assertThat(thumbnail.getWidth()).isEqualTo(640);
        assertThat(thumbnail.getHeight()).isEqualTo(480);
        assertThat(smaller.body().length).isLessThan(photo.length);
        // A kept picture never changes, so the owner's own device may keep it; nothing in between may.
        assertThat(full.headers().firstValue("Cache-Control").orElse("")).contains("private").contains("immutable").contains("max-age=31536000");
        // The browser is told to take the type as given and not to guess one from the bytes.
        assertThat(full.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
    }

    @Test
    void picturesAreServedOnlyToSignedInOwners() throws Exception {
        String idea = inspiration(null);
        String picture = uploaded(idea, new Part("arch.jpg", "image/jpeg", TestPictures.jpeg(200, 100)));
        String address = pictures(idea) + "/" + picture;

        assertThat(http.getBytesAs(null, address).statusCode()).as("without signing in").isEqualTo(401);
        assertThat(http.getBytesAs(null, address + "?size=THUMBNAIL").statusCode()).isEqualTo(401);
        assertThat(http.getBytesAs("not-a-token", address).statusCode()).isEqualTo(401);
        assertThat(http.getBytesAs(anna, address).statusCode()).isEqualTo(200);
        assertThat(http.getBytesAs(piotr, address).statusCode()).isEqualTo(200);
        // Nothing can be sent or removed without signing in either.
        HttpResponse<String> unsigned = http.postFilesAs("not-a-token", pictures(idea), List.of(new Part("a.png", "image/png", TestPictures.png(8, 8))));
        assertThat(unsigned.statusCode()).isEqualTo(401);
        assertThat(http.deleteAs("not-a-token", address).statusCode()).isEqualTo(401);
        assertThat(http.getBytesAs(anna, address).statusCode()).as("it is still there").isEqualTo(200);
    }

    @Test
    void aPictureIsOnlyFoundUnderItsOwnInspiration() throws Exception {
        String idea = inspiration(null);
        String other = inspiration(null);
        String picture = uploaded(idea, new Part("arch.jpg", "image/jpeg", TestPictures.jpeg(200, 100)));

        assertThat(http.getBytesAs(anna, pictures(other) + "/" + picture).statusCode()).isEqualTo(404);
        assertThat(http.getBytesAs(anna, pictures(idea) + "/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(http.getBytesAs(anna, pictures(UUID.randomUUID().toString()) + "/" + picture).statusCode()).isEqualTo(404);
        assertThat(http.deleteAs(anna, pictures(other) + "/" + picture).statusCode()).isEqualTo(404);
        // An id is an id: anything else in its place is refused before it is looked at.
        assertThat(http.getBytesAs(anna, pictures(idea) + "/..%2F..%2Fetc%2Fpasswd").statusCode()).isIn(400, 404);
        assertThat(http.getBytesAs(anna, pictures(idea) + "/not-an-id").statusCode()).isEqualTo(400);
        assertThat(http.getBytesAs(anna, pictures(idea) + "/" + picture + "?size=HUGE").statusCode()).isEqualTo(400);
    }

    // ------------------------------------------------------------ what is taken

    @Test
    void theKindOfAPictureComesFromTheFileItselfNotFromWhatTheSenderCallsIt() throws Exception {
        String idea = inspiration(null);

        HttpResponse<String> response = http.postFilesAs(anna, pictures(idea), List.of(
                new Part("definitely-a.jpg", "text/plain", TestPictures.png(120, 80))));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        Map<String, Object> kept = picturesOf(TestHttp.json(response)).get(0);
        assertThat(kept).containsEntry("contentType", "image/png");
        assertThat(http.getBytesAs(anna, pictures(idea) + "/" + kept.get("id")).headers().firstValue("Content-Type")).contains("image/png");
    }

    @Test
    void whatIsNotAPictureIsRefusedWhateverItIsCalled() throws Exception {
        String idea = inspiration(null);
        byte[] page = "<!doctype html><html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        byte[] vector = "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"/>".getBytes(StandardCharsets.UTF_8);
        byte[] cut = Arrays.copyOf(TestPictures.jpeg(400, 300), 60);

        assertThat(refusal(idea, new Part("photo.jpg", "image/jpeg", page)))
                .isEqualTo("files: “photo.jpg” is not a picture the planner keeps. Use a JPEG, PNG, GIF or WebP picture.");
        assertThat(refusal(idea, new Part("drawing.svg", "image/svg+xml", vector))).startsWith("files: “drawing.svg” is not a picture");
        assertThat(refusal(idea, new Part("broken.jpg", "image/jpeg", cut))).isEqualTo("files: “broken.jpg” could not be read as a picture.");
        assertThat(refusal(idea, new Part("nothing.png", "image/png", new byte[0]))).isEqualTo("files: “nothing.png” is empty.");

        assertThat(picturesOf(TestHttp.json(http.getAs(anna, "/api/inspirations/" + idea)))).isEmpty();
    }

    @Test
    void ifOneOfSeveralIsRefusedNoneIsKept() throws Exception {
        String idea = inspiration(null);
        long before = storedFiles();

        HttpResponse<String> response = http.postFilesAs(anna, pictures(idea), List.of(
                new Part("good.png", "image/png", TestPictures.png(64, 64)),
                new Part("notes.txt", "text/plain", "not a picture".getBytes(StandardCharsets.UTF_8))));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(picturesOf(TestHttp.json(http.getAs(anna, "/api/inspirations/" + idea)))).isEmpty();
        assertThat(storedFiles()).as("nothing was left on the disk").isEqualTo(before);
    }

    @Test
    void aKindTheServerCannotReadIsKeptAsItCame() throws Exception {
        String idea = inspiration(null);
        byte[] webp = TestPictures.webp();

        HttpResponse<String> response = http.postFilesAs(anna, pictures(idea), List.of(new Part("idea.webp", "image/webp", webp)));

        Map<String, Object> kept = picturesOf(TestHttp.json(response)).get(0);
        assertThat(kept).containsEntry("contentType", "image/webp");
        assertThat(kept.get("width")).isNull();
        assertThat(kept.get("height")).isNull();
        // There is no smaller version; asking for one gives the picture itself.
        HttpResponse<byte[]> smaller = http.getBytesAs(anna, pictures(idea) + "/" + kept.get("id") + "?size=THUMBNAIL");
        assertThat(smaller.body()).isEqualTo(webp);
        assertThat(smaller.headers().firstValue("Content-Type")).contains("image/webp");
    }

    @Test
    void aFilesNameNeverDecidesWhereItIsStored() throws Exception {
        String idea = inspiration(null);
        long before = storedFiles();

        String picture = uploaded(idea, new Part("../../../../tmp/escaped.png", "image/png", TestPictures.png(32, 32)));

        assertThat(STORED.resolve(picture)).exists();
        assertThat(storedFiles()).isEqualTo(before + 2);
        try (Stream<Path> files = Files.list(STORED)) {
            assertThat(files.map(file -> file.getFileName().toString()))
                    .allMatch(name -> name.matches("[0-9a-f-]{36}(\\.thumb)?"), "named after an id and nothing else");
        }
        assertThat(MEDIA.getParent().resolve("escaped.png")).doesNotExist();
    }

    @Test
    void anInspirationHoldsAtMostTenPictures() throws Exception {
        String idea = inspiration(null);
        List<Part> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ten.add(new Part(i + ".png", "image/png", TestPictures.png(16, 16)));
        }
        assertThat(http.postFilesAs(anna, pictures(idea), ten).statusCode()).isEqualTo(200);

        HttpResponse<String> eleventh = http.postFilesAs(anna, pictures(idea), List.of(new Part("11.png", "image/png", TestPictures.png(16, 16))));

        assertThat(eleventh.statusCode()).isEqualTo(400);
        assertThat(TestHttp.fieldMessages(eleventh)).containsExactly("files: An inspiration holds at most 10 pictures.");
        assertThat(picturesOf(TestHttp.json(http.getAs(anna, "/api/inspirations/" + idea)))).hasSize(10);
    }

    @Test
    void aFileOverTheLimitIsRefusedAsTooLarge() throws Exception {
        String idea = inspiration(null);
        byte[] tooLarge = Arrays.copyOf(TestPictures.jpeg(64, 64), 15 * 1024 * 1024 + 1024);

        HttpResponse<String> response = http.postFilesAs(anna, pictures(idea), List.of(new Part("huge.jpg", "image/jpeg", tooLarge)));

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat((String) TestHttp.json(response).get("type")).endsWith(":upload-too-large");
        assertThat(picturesOf(TestHttp.json(http.getAs(anna, "/api/inspirations/" + idea)))).isEmpty();
    }

    @Test
    void picturesNeedAnInspirationToBelongTo() throws Exception {
        HttpResponse<String> response = http.postFilesAs(anna, pictures(UUID.randomUUID().toString()), List.of(
                new Part("a.png", "image/png", TestPictures.png(16, 16))));

        assertThat(response.statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------ removing

    @Test
    void onePictureCanBeRemovedAndIsThenGoneFromTheDiskToo() throws Exception {
        String idea = inspiration(null);
        HttpResponse<String> both = http.postFilesAs(anna, pictures(idea), List.of(
                new Part("a.png", "image/png", TestPictures.png(32, 32)), new Part("b.png", "image/png", TestPictures.png(48, 48))));
        String first = (String) picturesOf(TestHttp.json(both)).get(0).get("id");
        String second = (String) picturesOf(TestHttp.json(both)).get(1).get("id");

        HttpResponse<String> removed = http.deleteAs(piotr, pictures(idea) + "/" + first);

        assertThat(removed.statusCode()).as(removed.body()).isEqualTo(200);
        assertThat(picturesOf(TestHttp.json(removed))).extracting(picture -> picture.get("id")).containsExactly(second);
        assertThat(STORED.resolve(first)).doesNotExist();
        assertThat(STORED.resolve(first + ".thumb")).doesNotExist();
        assertThat(STORED.resolve(second)).exists();
        assertThat(http.getBytesAs(anna, pictures(idea) + "/" + first).statusCode()).isEqualTo(404);
        assertThat(http.deleteAs(anna, pictures(idea) + "/" + first).statusCode()).isEqualTo(404);
    }

    @Test
    void picturesGoFromTheDiskWithTheirInspiration() throws Exception {
        String idea = inspiration(null);
        String picture = uploaded(idea, new Part("a.png", "image/png", TestPictures.png(32, 32)));
        String untouched = uploaded(inspiration(null), new Part("b.png", "image/png", TestPictures.png(32, 32)));

        assertThat(http.deleteAs(anna, "/api/inspirations/" + idea).statusCode()).isEqualTo(204);

        assertThat(STORED.resolve(picture)).doesNotExist();
        assertThat(STORED.resolve(picture + ".thumb")).doesNotExist();
        assertThat(STORED.resolve(untouched)).exists();
    }

    @Test
    void removingABoardWithItsInspirationsRemovesTheirPicturesAndKeepingThemKeepsThem() throws Exception {
        String flowers = boardId("Flowers");
        String dress = boardId("Dress");
        String peonies = uploaded(inspiration(null, flowers), new Part("a.png", "image/png", TestPictures.png(32, 32)));
        String veil = uploaded(inspiration(null, dress), new Part("b.png", "image/png", TestPictures.png(32, 32)));

        assertThat(http.deleteAs(anna, "/api/boards/" + flowers + "?inspirations=DELETE").statusCode()).isEqualTo(204);
        assertThat(http.deleteAs(anna, "/api/boards/" + dress + "?inspirations=KEEP").statusCode()).isEqualTo(204);

        assertThat(STORED.resolve(peonies)).doesNotExist();
        assertThat(STORED.resolve(veil)).exists();
    }

    @Test
    void aPictureWhoseFileIsMissingIsNotFoundRatherThanAnError() throws Exception {
        String idea = inspiration(null);
        String picture = uploaded(idea, new Part("a.png", "image/png", TestPictures.png(32, 32)));
        // As after a restore that brought the database back and not the files.
        Files.delete(STORED.resolve(picture));

        assertThat(http.getBytesAs(anna, pictures(idea) + "/" + picture).statusCode()).isEqualTo(404);
        assertThat(http.getAs(anna, "/api/inspirations/" + idea).statusCode()).as("the inspiration itself is fine").isEqualTo(200);
    }

    // ------------------------------------------------------------ helpers

    private static String pictures(String inspirationId) {
        return "/api/inspirations/" + inspirationId + "/pictures";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> picturesOf(Map<String, Object> inspiration) {
        return (List<Map<String, Object>>) inspiration.get("pictures");
    }

    private String inspiration(String url) throws Exception {
        return inspiration(url, null);
    }

    private String inspiration(String url, String boardId) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("url", url);
        request.put("boardId", boardId);
        request.put("title", "");
        request.put("note", "");
        request.put("tags", List.of());
        request.put("planItemId", null);
        HttpResponse<String> response = http.postJsonAs(anna, "/api/inspirations", TestHttp.jsonOf(request));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return (String) TestHttp.json(response).get("id");
    }

    /** @return the id of the picture */
    private String uploaded(String inspirationId, Part file) throws Exception {
        HttpResponse<String> response = http.postFilesAs(anna, pictures(inspirationId), List.of(file));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        List<Map<String, Object>> all = picturesOf(TestHttp.json(response));
        return (String) all.get(all.size() - 1).get("id");
    }

    /** What the server says about a file it does not take. */
    private String refusal(String inspirationId, Part file) throws Exception {
        HttpResponse<String> response = http.postFilesAs(anna, pictures(inspirationId), List.of(file));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        return String.join(" | ", TestHttp.fieldMessages(response));
    }

    private String boardId(String name) throws Exception {
        return TestHttp.jsonList(http.getAs(anna, "/api/boards")).stream()
                .filter(board -> name.equals(board.get("name")))
                .map(board -> (String) board.get("id"))
                .findFirst()
                .orElseThrow();
    }

    private static long storedFiles() throws IOException {
        try (Stream<Path> files = Files.list(STORED)) {
            return files.count();
        }
    }
}
