package io.hindsight.core.store;

import io.hindsight.core.privacy.Pseudonymizer;
import io.hindsight.model.Recording;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 기록 파일이 디스크에 닿는 <b>유일한 통로</b>.
 *
 * <h2>🔴 가명화와 쓰기가 한 함수 안에 묶여 있다</h2>
 * 이 둘을 따로 두면 언젠가 순서가 뒤집히거나 한쪽이 빠진다. 그리고 그건
 * <b>진짜 사용자 데이터가 디스크에 남는 순간</b>이다. 나중에 코드를 고치는 사람이
 * 순서를 바꾸려면 {@link #write} 안을 고쳐야 하고, 그건 눈에 띈다.
 *
 * <pre>
 *   write(recording)
 *      │
 *      ├─ 1. 가명화                ← 🔴 여기를 건너뛰는 경로가 «없다»
 *      ├─ 2. JSON 으로
 *      ├─ 3. 파일로
 *      └─ 4. 총량이 넘으면 오래된 것부터 지운다
 * </pre>
 *
 * <h2>🔴 총량 상한을 여기서 지키는 이유</h2>
 * 상한을 부르는 쪽에 맡기면, 부르는 자리가 둘이 되는 날 한쪽이 잊는다. 그리고 그날이
 * 디스크가 차서 <b>앱이 두 번째로 죽는</b> 날이다. 지우는 쪽도 이 통로 안에 둔다.
 */
public final class RecordingStore {

    private final Path directory;
    private final long maxBytes;
    private final Pseudonymizer pseudonymizer;
    private final RecordingCodec codec = new RecordingCodec();

    /**
     * @param directory 기록을 모아 두는 폴더
     * @param maxBytes  폴더 전체의 상한. 넘으면 «오래된 것부터» 지운다
     * @param pseudonymKey 가명화 키. {@code null} 이면 가명화 대상을 버린다
     */
    public RecordingStore(Path directory, long maxBytes, String pseudonymKey) {
        this.directory = directory;
        this.maxBytes = maxBytes;
        this.pseudonymizer = new Pseudonymizer(pseudonymKey);
    }

    /** 가명화 키가 실제로 있나. 없으면 기록이 덜 쓸모 있다는 것을 부르는 쪽이 알아야 한다. */
    public boolean hasPseudonymKey() {
        return pseudonymizer.hasKey();
    }

    public Path directory() {
        return directory;
    }

    /**
     * 기록 하나를 디스크에 남긴다. <b>가명화가 이 안에서 먼저 일어난다.</b>
     *
     * @return 실제로 쓴 파일 경로
     */
    public Path write(Recording recording) {
        // 1. 🔴 가명화가 먼저다. 이 줄과 다음 줄 사이에 다른 것이 끼어들면 안 된다.
        Recording safe = pseudonymizer.apply(recording);

        // 2~3. JSON 으로 만들어 파일에 쓴다.
        Path target = directory.resolve(fileNameFor(safe));
        codec.write(safe, target);

        // 4. 총량을 넘겼으면 오래된 것부터 지운다.
        enforceStoreLimit();

        return target;
    }

    /** 폴더에 있는 기록 파일을 최근 것부터. */
    public List<Path> list() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparingLong(RecordingStore::lastModifiedOrZero).reversed())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("기록 폴더를 읽지 못했다: " + directory, e);
        }
    }

    public Recording read(Path file) {
        return codec.read(file);
    }

    private static String fileNameFor(Recording recording) {
        return recording.capturedAt().toString().replace(':', '-') + "_" + recording.id() + ".json";
    }

    /**
     * 총량이 상한을 넘으면 오래된 것부터 지운다.
     *
     * <p>🔴 방금 쓴 것을 지우게 될 수도 있다. 그래도 그렇게 한다 — 「가장 최근 것은 남긴다」는
     * 예외를 두면 상한이 상한이 아니게 되고, 파일 하나가 상한보다 클 때 폴더가 계속 자란다.
     * 대신 지운 사실은 {@link #evictedOnLastWrite()} 로 알 수 있게 둔다.
     */
    private void enforceStoreLimit() {
        evictedOnLastWrite = 0;
        List<Path> files = list();
        long total = 0;
        for (Path file : files) {
            total += sizeOrZero(file);
        }
        if (total <= maxBytes) {
            return;
        }
        List<Path> oldestFirst = new ArrayList<>(files);
        java.util.Collections.reverse(oldestFirst);
        for (Path file : oldestFirst) {
            if (total <= maxBytes) {
                break;
            }
            long size = sizeOrZero(file);
            try {
                Files.deleteIfExists(file);
                total -= size;
                evictedOnLastWrite++;
            } catch (IOException e) {
                // 지우지 못한 파일 때문에 쓰기 자체를 실패시키지 않는다.
                // 기록이 남는 것이 더 중요하고, 총량은 다음 쓰기에서 다시 본다.
                break;
            }
        }
    }

    private int evictedOnLastWrite;

    /** 마지막 쓰기에서 총량 때문에 지운 파일 수. 0 이면 안 지웠다. */
    public int evictedOnLastWrite() {
        return evictedOnLastWrite;
    }

    private static long sizeOrZero(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private static long lastModifiedOrZero(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
