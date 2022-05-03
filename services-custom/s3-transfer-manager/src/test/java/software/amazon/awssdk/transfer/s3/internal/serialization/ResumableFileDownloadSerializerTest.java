/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.transfer.s3.internal.serialization;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.RequestPayer;
import software.amazon.awssdk.testutils.RandomTempFile;
import software.amazon.awssdk.transfer.s3.DownloadFileRequest;
import software.amazon.awssdk.transfer.s3.ResumableFileDownload;
import software.amazon.awssdk.transfer.s3.TransferRequestOverrideConfiguration;
import software.amazon.awssdk.transfer.s3.progress.LoggingTransferListener;
import software.amazon.awssdk.utils.DateUtils;

class ResumableFileDownloadSerializerTest {

    private static final Path PATH = RandomTempFile.randomUncreatedFile().toPath();

    private static final Map<String, GetObjectRequest> GET_OBJECT_REQUESTS;

    static {
        Map<String, GetObjectRequest> requests = new HashMap<>();
        requests.put("EMPTY", GetObjectRequest.builder().build());
        requests.put("STANDARD", GetObjectRequest.builder().bucket("BUCKET").key("KEY").build());
        requests.put("ALL_TYPES", GetObjectRequest.builder()
                                                  .bucket("BUCKET")
                                                  .key("KEY")
                                                  .partNumber(1)
                                                  .ifModifiedSince(DateUtils.parseIso8601Date("2020-01-01T12:10:30Z"))
                                                  .checksumMode(ChecksumMode.ENABLED)
                                                  .requestPayer(RequestPayer.REQUESTER)
                                                  .build());
        GET_OBJECT_REQUESTS = Collections.unmodifiableMap(requests);
    }

    @ParameterizedTest
    @MethodSource("downloadObjects")
    void serializeDeserialize_ShouldWorkForAllDownloads(ResumableFileDownload download)  {
        byte[] serializedDownload = ResumableFileDownloadSerializer.toJson(download);
        ResumableFileDownload deserializedDownload = ResumableFileDownloadSerializer.fromJson(serializedDownload);

        assertThat(deserializedDownload).isEqualTo(download);
    }

    @Test
    void serializeDeserialize_fromStoredString_ShouldWork()  {
        ResumableFileDownload download =
            ResumableFileDownload.builder()
                                 .downloadFileRequest(d -> d.destination(Paths.get("test/request"))
                                                            .getObjectRequest(GET_OBJECT_REQUESTS.get("ALL_TYPES")))
                                 .bytesTransferred(1000L)
                                 .fileLastModified(DateUtils.parseIso8601Date("2022-03-08T10:15:30Z"))
                                 .totalSizeInBytes(5000L)
                                 .s3ObjectLastModified(DateUtils.parseIso8601Date("2022-03-10T08:21:00Z"))
                                 .build();

        byte[] serializedDownload = ResumableFileDownloadSerializer.toJson(download);
        assertThat(new String(serializedDownload, StandardCharsets.UTF_8)).isEqualTo(SERIALIZED_DOWNLOAD_OBJECT);

        ResumableFileDownload deserializedDownload =
            ResumableFileDownloadSerializer.fromJson(SERIALIZED_DOWNLOAD_OBJECT.getBytes(StandardCharsets.UTF_8));
        assertThat(deserializedDownload).isEqualTo(download);
    }

    @Test
    void serializeDeserialize_DoesNotPersistConfiguration()  {
        ResumableFileDownload download =
            ResumableFileDownload.builder()
                                 .downloadFileRequest(d -> d.destination(PATH)
                                                            .getObjectRequest(GET_OBJECT_REQUESTS.get("STANDARD"))
                                                            .overrideConfiguration(c -> c.addListener(LoggingTransferListener.create())))
                                 .bytesTransferred(1000L)
                                 .build();

        byte[] serializedDownload = ResumableFileDownloadSerializer.toJson(download);
        ResumableFileDownload deserializedDownload = ResumableFileDownloadSerializer.fromJson(serializedDownload);

        DownloadFileRequest fileRequestWithoutConfig =
            download.downloadFileRequest().copy(r -> r.overrideConfiguration((TransferRequestOverrideConfiguration) null));
        assertThat(deserializedDownload).isEqualTo(download.copy(d -> d.downloadFileRequest(fileRequestWithoutConfig)));
    }

    @Test
    void serializeDeserialize_DoesNotPersistRequestOverrideConfiguration()  {
        GetObjectRequest requestWithOverride =
            GetObjectRequest.builder()
                            .bucket("BUCKET")
                            .key("KEY")
                            .overrideConfiguration(c -> c.apiCallAttemptTimeout(Duration.ofMillis(20)).build())
                            .build();

        DownloadFileRequest downloadFileRequest = DownloadFileRequest.builder()
                                                                     .destination(PATH)
                                                                     .getObjectRequest(requestWithOverride)
                                                                     .build();

        ResumableFileDownload download = ResumableFileDownload.builder()
                                                              .downloadFileRequest(downloadFileRequest)
                                                              .build();

        byte[] serializedDownload = ResumableFileDownloadSerializer.toJson(download);
        ResumableFileDownload deserializedDownload = ResumableFileDownloadSerializer.fromJson(serializedDownload);

        GetObjectRequest requestWithoutOverride =
            requestWithOverride.copy(r -> r.overrideConfiguration((AwsRequestOverrideConfiguration) null));
        DownloadFileRequest fileRequestCopy = downloadFileRequest.copy(r -> r.getObjectRequest(requestWithoutOverride));
        assertThat(deserializedDownload).isEqualTo(download.copy(d -> d.downloadFileRequest(fileRequestCopy)));
    }

    public static Collection<ResumableFileDownload> downloadObjects() {
        return Stream.of(differentDownloadSettings(),
                         differentGetObjects())
                     .flatMap(Collection::stream).collect(Collectors.toList());
    }

    private static List<ResumableFileDownload> differentGetObjects() {
        return GET_OBJECT_REQUESTS.values()
                                  .stream()
                                  .map(request -> resumableFileDownload(1000L, null, Instant.now(), null,
                                                                        downloadRequest(PATH, request)))
                                  .collect(Collectors.toList());
    }

    private static List<ResumableFileDownload> differentDownloadSettings() {
        DownloadFileRequest request = downloadRequest(PATH, GET_OBJECT_REQUESTS.get("STANDARD"));
        return Arrays.asList(
            resumableFileDownload(null, null, null, null, request),
            resumableFileDownload(1000L, null, null, null, request),
            resumableFileDownload(1000L, null, Instant.now(), null, request),
            resumableFileDownload(1000L, 2000L, Instant.now(), Instant.now(), request),
            resumableFileDownload(Long.MAX_VALUE, Long.MAX_VALUE, Instant.now(), Instant.now(), request)
        );
    }

    private static ResumableFileDownload resumableFileDownload(Long bytesTransferred,
                                                               Long totalSizeInBytes,
                                                               Instant fileLastModified,
                                                               Instant s3ObjectLastModified,
                                                               DownloadFileRequest request) {
        ResumableFileDownload.Builder builder = ResumableFileDownload.builder()
                                                                     .downloadFileRequest(request)
                                                                     .bytesTransferred(bytesTransferred);
        if (totalSizeInBytes != null) {
            builder.totalSizeInBytes(totalSizeInBytes);
        }
        if (fileLastModified != null) {
            builder.fileLastModified(fileLastModified);
        }
        if (s3ObjectLastModified != null) {
            builder.s3ObjectLastModified(s3ObjectLastModified);
        }
        return builder.build();
    }

    private static DownloadFileRequest downloadRequest(Path path, GetObjectRequest request) {
        return DownloadFileRequest.builder()
                                  .getObjectRequest(request)
                                  .destination(path)
                                  .build();
    }

    private static final String SERIALIZED_DOWNLOAD_OBJECT = "{\"bytesTransferred\":1000,\"fileLastModified\":1646734530.000,"
                                                             + "\"totalSizeInBytes\":5000,\"s3ObjectLastModified\":1646900460"
                                                             + ".000,\"downloadFileRequest\":{\"destination\":\"test/request\","
                                                             + "\"getObjectRequest\":{\"Bucket\":\"BUCKET\","
                                                             + "\"If-Modified-Since\":1577880630.000,\"Key\":\"KEY\","
                                                             + "\"x-amz-request-payer\":\"requester\",\"partNumber\":1,"
                                                             + "\"x-amz-checksum-mode\":\"ENABLED\"}}}";
}
