package io.reliablepush.payload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.reliablepush.payload.internal.Json;
import io.reliablepush.payload.internal.JsonWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Runs every file of src/test/resources/vectors (the contract other languages implement; see its README). */
class GoldenVectorsTest {

    private static final Path DIR = Paths.get("src", "test", "resources", "vectors");

    private static List<Path> vectorFiles() throws IOException {
        List<Path> files = new ArrayList<Path>();
        DirectoryStream<Path> ds = Files.newDirectoryStream(DIR, "*.json");
        try {
            for (Path p : ds) {
                files.add(p);
            }
        } finally {
            ds.close();
        }
        Collections.sort(files);
        return files;
    }

    @TestFactory
    List<DynamicTest> everyVector() throws IOException {
        List<DynamicTest> tests = new ArrayList<DynamicTest>();
        for (final Path file : vectorFiles()) {
            tests.add(DynamicTest.dynamicTest(file.getFileName().toString(), new org.junit.jupiter.api.function.Executable() {
                @Override
                public void execute() throws Throwable {
                    run(file);
                }
            }));
        }
        return tests;
    }

    @Test
    void thereAreAtLeastTwelveVectorsCoveringEveryOperationAndEveryErrorCode() throws IOException {
        TreeSet<String> operations = new TreeSet<String>();
        TreeSet<String> codes = new TreeSet<String>();
        List<Path> files = vectorFiles();
        for (Path file : files) {
            Map<?, ?> v = (Map<?, ?>) Json.read(read(file));
            operations.add((String) v.get("operation"));
            Object error = ((Map<?, ?>) v.get("expect")).get("error");
            if (error != null) {
                codes.add((String) error);
            }
        }
        assertThat(files.size()).isGreaterThanOrEqualTo(12);
        assertThat(operations).containsExactly("apns.build", "apns.decorate", "fcm.build", "fcm.data", "fcm.decorate",
                "trackingIds.isValid");
        TreeSet<String> all = new TreeSet<String>();
        for (PayloadException.Code c : PayloadException.Code.values()) {
            all.add(c.name());
        }
        assertThat(codes).isEqualTo(all);
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static void run(Path file) throws IOException {
        Map<String, Object> vector = (Map<String, Object>) Json.read(read(file));
        String operation = (String) vector.get("operation");
        Map<String, Object> input = (Map<String, Object>) vector.get("input");
        Map<String, Object> expect = (Map<String, Object>) vector.get("expect");
        assertThat(vector.get("name")).as("name").isEqualTo(stripExtension(file));

        if (expect.containsKey("error")) {
            String code = (String) expect.get("error");
            try {
                apply(operation, input);
                fail("expected " + code + " but the operation succeeded");
            } catch (PayloadException e) {
                assertThat(e.code().name()).isEqualTo(code);
            }
        } else {
            assertThat(apply(operation, input)).isEqualTo(JsonWriter.write(expect.get("output")));
        }
    }

    private static String stripExtension(Path file) {
        String n = file.getFileName().toString();
        return n.substring(0, n.length() - ".json".length());
    }

    /** Runs the operation and returns its result as compact JSON. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String apply(String operation, Map<String, Object> in) {
        String tid = (String) in.get("trackingId");
        switch (operation) {
            case "trackingIds.isValid":
                return String.valueOf(TrackingIds.isValid((String) in.get("id")));
            case "apns.build":
                return ApnsPayloads.build(tid, (String) in.get("title"), (String) in.get("body"),
                        (Map<String, ?>) in.get("custom"));
            case "apns.decorate": {
                if (in.containsKey("apnsJson")) {
                    return ApnsPayloads.decorate((String) in.get("apnsJson"), tid);
                }
                String viaString = ApnsPayloads.decorate(JsonWriter.write(in.get("apns")), tid);
                String viaMap = JsonWriter.write(ApnsPayloads.decorate((Map<String, ?>) in.get("apns"), tid));
                assertThat(viaMap).as("Map overload agrees with the String overload").isEqualTo(viaString);
                return viaString;
            }
            case "fcm.build":
                return FcmPayloads.build((String) in.get("token"), tid, (String) in.get("title"), (String) in.get("body"),
                        (Map) in.get("data"));
            case "fcm.data":
                return JsonWriter.write(FcmPayloads.data(tid, (String) in.get("title"), (String) in.get("body"),
                        (Map) in.get("data")));
            case "fcm.decorate":
                return FcmPayloads.decorate(in.containsKey("fcmJson") ? (String) in.get("fcmJson")
                        : JsonWriter.write(in.get("fcm")), tid);
            default:
                throw new IllegalStateException("unknown operation " + operation);
        }
    }
}
