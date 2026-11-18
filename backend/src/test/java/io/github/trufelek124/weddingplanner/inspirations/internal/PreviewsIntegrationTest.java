package io.github.trufelek124.weddingplanner.inspirations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestClock;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestHttp.Part;
import io.github.trufelek124.weddingplanner.TestWedding;
import io.github.trufelek124.weddingplanner.inspirations.internal.StandIn.Answer;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * From a saved link to its preview, through the API. The "internet" is a stand-in server on this
 * machine; the rules for where the server may go are the real ones with that one door opened.
 */
@Import(PreviewsIntegrationTest.StandInOnly.class)
class PreviewsIntegrationTest extends PostgresIntegrationTest {

    private static final StandIn WEB = StandIn.shared();
    private static final Path STORED = MEDIA.resolve("inspirations");

    @TestConfiguration
    static class StandInOnly {
        @Bean
        @Primary
        Destinations standInDestinations() {
            return new StandInDestinations(WEB.port());
        }
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PreviewWorker worker;

    TestHttp http;
    String anna;
    String piotr;

    @BeforeEach
    void aFreshWedding() throws Exception {
        TestWedding wedding = TestWedding.fresh(jdbc, port);
        http = wedding.http;
        anna = wedding.anna;
        piotr = wedding.piotr;
        WEB.forget();
    }

    // ------------------------------------------------------------ what a link gives

    @Test
    void aSavedLinkGetsThePagesTitleAndItsPreviewPicture() throws Exception {
        WEB.serve("/peonies", Answer.html("""
                <html><head>
                  <title>Blog | Flowers</title>
                  <meta property="og:title" content="Peonies &amp; garden roses">
                  <meta property="og:image" content="/pictures/peonies.jpg">
                </head><body><script>document.title = 'changed by a script'</script></body></html>
                """));
        WEB.serve("/pictures/peonies.jpg", Answer.of(200, "image/jpeg", TestPictures.jpeg(1200, 900)));

        Map<String, Object> saved = saved(link("/peonies"));
        // Saving does not wait for the other server: the answer says a preview is on its way.
        assertThat(saved).containsEntry("previewStatus", "PENDING").containsEntry("previewTitle", "");
        assertThat(WEB.seen()).isEmpty();

        assertThat(worker.runOnce()).isEqualTo(1);

        Map<String, Object> afterwards = read(saved);
        assertThat(afterwards)
                .containsEntry("previewStatus", "FETCHED")
                .containsEntry("previewTitle", "Peonies & garden roses")
                // What a person typed, here nothing, is not touched; nor is the count of their saves.
                .containsEntry("title", "")
                .containsEntry("version", 0);
        List<Map<String, Object>> pictures = pictures(afterwards);
        assertThat(pictures).hasSize(1);
        assertThat(pictures.get(0))
                .containsEntry("preview", true).containsEntry("contentType", "image/jpeg").containsEntry("width", 1200).containsEntry("height", 900);
        // The picture is the planner's own copy now, kept and served like any other: to the owners only.
        String address = "/api/inspirations/" + saved.get("id") + "/pictures/" + pictures.get(0).get("id");
        assertThat(http.getBytesAs(piotr, address + "?size=THUMBNAIL").statusCode()).isEqualTo(200);
        assertThat(http.getBytesAs(null, address).statusCode()).isEqualTo(401);
        // Once fetched, it is not fetched again.
        assertThat(worker.runOnce()).isZero();
        assertThat(WEB.hits("/peonies")).isEqualTo(1);
    }

    @Test
    void aPageWithOnlyATitleGivesItsTitle() throws Exception {
        WEB.serve("/article", Answer.html("<html><head><title>  Ten ideas\n for a   garden wedding </title></head><body>…</body></html>"));

        Map<String, Object> saved = saved(link("/article"));
        worker.runOnce();

        assertThat(read(saved)).containsEntry("previewStatus", "FETCHED").containsEntry("previewTitle", "Ten ideas for a garden wedding");
        assertThat(pictures(read(saved))).isEmpty();
    }

    @Test
    void aLinkThatIsAPictureIsKeptAsThatPicture() throws Exception {
        WEB.serve("/photo.png", Answer.of(200, "image/png", TestPictures.png(640, 480)));

        Map<String, Object> saved = saved(link("/photo.png"));
        worker.runOnce();

        assertThat(read(saved)).containsEntry("previewStatus", "FETCHED").containsEntry("previewTitle", "");
        assertThat(pictures(read(saved)).get(0)).containsEntry("preview", true).containsEntry("contentType", "image/png");
    }

    @Test
    void theTitleAPersonTypedStaysAndThePagesIsKeptBesideIt() throws Exception {
        WEB.serve("/dress", Answer.html("<html><head><meta property='og:title' content='Shop: dress no. 4711'></head></html>"));
        Map<String, Object> request = link("/dress");
        request.put("title", "The one with lace sleeves");

        Map<String, Object> saved = saved(request);
        worker.runOnce();

        assertThat(read(saved)).containsEntry("title", "The one with lace sleeves").containsEntry("previewTitle", "Shop: dress no. 4711");
    }

    @Test
    void anOverlongTitleIsCutAndAScriptInItIsOnlyText() throws Exception {
        WEB.serve("/long", Answer.html("<html><head><title>" + "very ".repeat(100) + "</title></head></html>"));
        WEB.serve("/sly", Answer.html("<html><head><meta property='og:title' content='&lt;script&gt;alert(1)&lt;/script&gt;'></head></html>"));

        Map<String, Object> longOne = saved(link("/long"));
        Map<String, Object> sly = saved(link("/sly"));
        worker.runOnce();

        assertThat((String) read(longOne).get("previewTitle")).hasSizeLessThanOrEqualTo(200).endsWith("…");
        // Kept as the text it is; it is for the client to show it as text, which the web app does.
        assertThat(read(sly)).containsEntry("previewTitle", "<script>alert(1)</script>");
    }

    // ------------------------------------------------------------ what a link does not give

    @Test
    void aLinkThatGivesNothingIsMarkedSoAndTheCardHasItsSourceAndNote() throws Exception {
        WEB.serve("/login-wall", Answer.status(403));
        WEB.serve("/bare", Answer.html("<html><head></head><body>no title, no picture</body></html>"));
        WEB.serve("/document.pdf", Answer.of(200, "application/pdf", "%PDF-1.7".getBytes(StandardCharsets.UTF_8)));
        Map<String, Object> request = link("/login-wall");
        request.put("note", "The reel with the long table.");

        Map<String, Object> refused = saved(request);
        Map<String, Object> gone = saved(link("/not-there"));
        Map<String, Object> bare = saved(link("/bare"));
        Map<String, Object> document = saved(link("/document.pdf"));
        assertThat(worker.runOnce()).isEqualTo(4);

        for (Map<String, Object> one : List.of(refused, gone, bare, document)) {
            assertThat(read(one)).containsEntry("previewStatus", "NONE").containsEntry("previewTitle", "");
            assertThat(pictures(read(one))).isEmpty();
        }
        assertThat(read(refused)).containsEntry("source", "WEB").containsEntry("note", "The reel with the long table.");
        // Nothing is tried a second time.
        assertThat(worker.runOnce()).isZero();
    }

    @Test
    void aPictureCanStillBeAttachedByHandWhenThePreviewGaveNone() throws Exception {
        WEB.serve("/login-wall", Answer.status(403));
        Map<String, Object> saved = saved(link("/login-wall"));
        worker.runOnce();

        HttpResponse<String> attached = http.postFilesAs(anna, "/api/inspirations/" + saved.get("id") + "/pictures", List.of(
                new Part("screenshot.png", "image/png", TestPictures.png(400, 800))));

        assertThat(attached.statusCode()).as(attached.body()).isEqualTo(200);
        assertThat(pictures(TestHttp.json(attached)).get(0)).containsEntry("preview", false);
        assertThat(TestHttp.json(attached)).containsEntry("previewStatus", "NONE");
    }

    @Test
    void whatThePageCallsItsPictureMustBeAPicture() throws Exception {
        WEB.serve("/page", Answer.html("<html><head><title>A page</title><meta property='og:image' content='/not-a-picture'></head></html>"));
        WEB.serve("/not-a-picture", Answer.of(200, "image/jpeg", "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8)));
        WEB.serve("/huge", Answer.html("<html><head><title>Huge</title><meta property='og:image' content='/huge.png'></head></html>"));
        byte[] claimsToBeEnormous = TestPictures.png(4, 4);
        System.arraycopy(new byte[] {0x00, 0x00, 0x75, 0x30, 0x00, 0x00, 0x75, 0x30}, 0, claimsToBeEnormous, 16, 8);
        WEB.serve("/huge.png", Answer.of(200, "image/png", claimsToBeEnormous));
        long before = filesStored();

        Map<String, Object> page = saved(link("/page"));
        Map<String, Object> huge = saved(link("/huge"));
        worker.runOnce();

        // The title is worth keeping; the "picture" is not kept, whatever its sender called it.
        assertThat(read(page)).containsEntry("previewStatus", "FETCHED").containsEntry("previewTitle", "A page");
        assertThat(pictures(read(page))).isEmpty();
        assertThat(pictures(read(huge))).isEmpty();
        assertThat(filesStored()).isEqualTo(before);
    }

    // ------------------------------------------------------------ where the server does not go

    @Test
    void aLinkIntoTheServersOwnNetworkIsKeptButNeverFetched() throws Exception {
        WEB.serve("/secret", Answer.html("<title>the admin page</title>"));
        List<String> inside = List.of(
                "http://localhost:" + WEB.port() + "/secret",
                "http://10.0.0.5/admin",
                "http://169.254.169.254/latest/meta-data/",
                "http://192.168.1.1/",
                "http://[::1]:" + WEB.port() + "/secret",
                "http://127.0.0.1:" + (WEB.port() - 1) + "/secret",
                "https://user:password@127.0.0.1:" + WEB.port() + "/secret");
        List<Map<String, Object>> kept = new java.util.ArrayList<>();
        for (String address : inside) {
            kept.add(saved(absolute(address)));
        }

        assertThat(worker.runOnce()).isEqualTo(inside.size());

        for (Map<String, Object> one : kept) {
            // The owner may keep any link; the answer never says why there is no preview.
            assertThat(read(one)).as((String) one.get("url")).containsEntry("previewStatus", "NONE").containsEntry("previewTitle", "");
            assertThat(pictures(read(one))).isEmpty();
        }
        assertThat(WEB.seen()).as("requests that reached the stand-in").isEmpty();
    }

    @Test
    void aPageCannotSendTheServerInsideEither() throws Exception {
        WEB.serve("/redirects-inside", Answer.redirect("http://10.11.12.13/admin"));
        WEB.serve("/picture-inside", Answer.html("""
                <html><head><title>Looks harmless</title>
                <meta property="og:image" content="http://169.254.169.254/latest/meta-data/iam/security-credentials/"></head></html>
                """));
        WEB.serve("/picture-on-this-machine", Answer.html(
                "<html><head><title>Also harmless</title><meta property='og:image' content='http://localhost:" + WEB.port() + "/secret'></head></html>"));
        WEB.serve("/secret", Answer.of(200, "image/png", TestPictures.png(10, 10)));

        Map<String, Object> redirected = saved(link("/redirects-inside"));
        Map<String, Object> pictureInside = saved(link("/picture-inside"));
        Map<String, Object> pictureHere = saved(link("/picture-on-this-machine"));
        worker.runOnce();

        assertThat(read(redirected)).containsEntry("previewStatus", "NONE");
        assertThat(read(pictureInside)).containsEntry("previewTitle", "Looks harmless");
        assertThat(pictures(read(pictureInside))).isEmpty();
        assertThat(pictures(read(pictureHere))).isEmpty();
        assertThat(WEB.hits("/secret")).isZero();
    }

    // ------------------------------------------------------------ trying again, and changes meanwhile

    @Test
    void aServerThatIsUnwellIsTriedAgainLaterAndThenGivenUpOn() throws Exception {
        WEB.serve("/unwell", Answer.status(503));
        Map<String, Object> saved = saved(link("/unwell"));

        worker.runOnce();
        assertThat(read(saved)).containsEntry("previewStatus", "PENDING");
        assertThat(worker.runOnce()).as("not again at once").isZero();

        TestClock.advance(Duration.ofMinutes(3));
        worker.runOnce();
        assertThat(read(saved)).containsEntry("previewStatus", "PENDING");
        assertThat(WEB.hits("/unwell")).isEqualTo(2);

        TestClock.advance(Duration.ofMinutes(10));
        worker.runOnce();
        assertThat(read(saved)).as("three tries are enough").containsEntry("previewStatus", "NONE");
        TestClock.advance(Duration.ofHours(1));
        assertThat(worker.runOnce()).isZero();
        assertThat(WEB.hits("/unwell")).isEqualTo(3);
    }

    @Test
    void aServerThatRecoversGivesItsPreviewOnTheNextTry() throws Exception {
        WEB.serve("/recovering", Answer.status(503));
        Map<String, Object> saved = saved(link("/recovering"));
        worker.runOnce();

        WEB.serve("/recovering", Answer.html("<title>Back again</title>"));
        TestClock.advance(Duration.ofMinutes(3));
        worker.runOnce();

        assertThat(read(saved)).containsEntry("previewStatus", "FETCHED").containsEntry("previewTitle", "Back again");
    }

    @Test
    void changingTheLinkDropsTheOldPreviewAndFetchesTheNewOne() throws Exception {
        WEB.serve("/first", Answer.html("<html><head><title>First</title><meta property='og:image' content='/first.png'></head></html>"));
        WEB.serve("/first.png", Answer.of(200, "image/png", TestPictures.png(100, 100)));
        WEB.serve("/second", Answer.html("<html><head><title>Second</title></head></html>"));
        Map<String, Object> saved = saved(link("/first"));
        http.postFilesAs(anna, "/api/inspirations/" + saved.get("id") + "/pictures", List.of(new Part("own.png", "image/png", TestPictures.png(50, 50))));
        worker.runOnce();
        List<Map<String, Object>> both = pictures(read(saved));
        assertThat(both).extracting(picture -> picture.get("preview")).containsExactly(false, true);
        String fetched = (String) both.get(1).get("id");

        Map<String, Object> change = link("/second");
        change.put("version", 0);
        HttpResponse<String> changed = http.putJsonAs(anna, "/api/inspirations/" + saved.get("id"), TestHttp.jsonOf(change));

        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);
        assertThat(TestHttp.json(changed)).containsEntry("previewStatus", "PENDING").containsEntry("previewTitle", "");
        // The picture fetched for the old link is gone, from the disk too; the one a person sent stays.
        assertThat(pictures(TestHttp.json(changed))).extracting(picture -> picture.get("preview")).containsExactly(false);
        assertThat(STORED.resolve(fetched)).doesNotExist();
        worker.runOnce();
        assertThat(read(saved)).containsEntry("previewStatus", "FETCHED").containsEntry("previewTitle", "Second");
    }

    @Test
    void changingAnythingButTheLinkLeavesThePreviewAlone() throws Exception {
        WEB.serve("/stays", Answer.html("<title>Stays</title>"));
        Map<String, Object> saved = saved(link("/stays"));
        worker.runOnce();

        Map<String, Object> change = link("/stays");
        change.put("note", "A note added later.");
        change.put("version", 0);
        HttpResponse<String> changed = http.putJsonAs(anna, "/api/inspirations/" + saved.get("id"), TestHttp.jsonOf(change));

        assertThat(TestHttp.json(changed)).containsEntry("previewStatus", "FETCHED").containsEntry("previewTitle", "Stays");
        assertThat(worker.runOnce()).isZero();
        assertThat(WEB.hits("/stays")).isEqualTo(1);
    }

    @Test
    void takingTheLinkAwayTakesItsPreviewAway() throws Exception {
        WEB.serve("/first", Answer.html("<html><head><title>First</title><meta property='og:image' content='/first.png'></head></html>"));
        WEB.serve("/first.png", Answer.of(200, "image/png", TestPictures.png(100, 100)));
        Map<String, Object> saved = saved(link("/first"));
        worker.runOnce();

        Map<String, Object> change = absolute(null);
        change.put("version", 0);
        HttpResponse<String> changed = http.putJsonAs(anna, "/api/inspirations/" + saved.get("id"), TestHttp.jsonOf(change));

        assertThat(TestHttp.json(changed)).containsEntry("previewStatus", "NONE").containsEntry("previewTitle", "").containsEntry("source", "PHOTO");
        assertThat(pictures(TestHttp.json(changed))).isEmpty();
        assertThat(worker.runOnce()).isZero();
    }

    @Test
    void anInspirationDeletedWhileItWaitsIsSimplyNotFetched() throws Exception {
        WEB.serve("/short-lived", Answer.html("<title>Short-lived</title>"));
        Map<String, Object> saved = saved(link("/short-lived"));

        assertThat(http.deleteAs(anna, "/api/inspirations/" + saved.get("id")).statusCode()).isEqualTo(204);

        assertThat(worker.runOnce()).isZero();
        assertThat(WEB.seen()).isEmpty();
    }

    // ------------------------------------------------------------ helpers

    private static Map<String, Object> link(String standInPath) {
        return absolute(WEB.at(standInPath).toString());
    }

    private static Map<String, Object> absolute(String url) {
        Map<String, Object> request = new HashMap<>();
        request.put("url", url);
        request.put("boardId", null);
        request.put("title", "");
        request.put("note", "");
        request.put("tags", List.of());
        request.put("planItemId", null);
        return request;
    }

    private Map<String, Object> saved(Map<String, Object> request) throws Exception {
        HttpResponse<String> response = http.postJsonAs(anna, "/api/inspirations", TestHttp.jsonOf(request));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return TestHttp.json(response);
    }

    private Map<String, Object> read(Map<String, Object> inspiration) throws Exception {
        HttpResponse<String> response = http.getAs(anna, "/api/inspirations/" + inspiration.get("id"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return TestHttp.json(response);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> pictures(Map<String, Object> inspiration) {
        return (List<Map<String, Object>>) inspiration.get("pictures");
    }

    private static long filesStored() throws Exception {
        try (var files = Files.list(STORED)) {
            return files.count();
        }
    }
}
