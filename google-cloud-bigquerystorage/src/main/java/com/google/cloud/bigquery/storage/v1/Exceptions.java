/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.bigquery.storage.v1;

import com.google.api.gax.grpc.GrpcStatusCode;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.Status;
import io.grpc.Status.Code;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/** Exceptions for Storage Client Libraries. */
public final class Exceptions {
  /** Main Storage Exception. Might contain map of streams to errors for that stream. */
  public static class StorageException extends RuntimeException {

    private final ImmutableMap<String, GrpcStatusCode> errors;
    private final String streamName;

    private StorageException() {
      this(null, null, null, ImmutableMap.of());
    }

    private StorageException(
        @Nullable String message,
        @Nullable Throwable cause,
        @Nullable String streamName,
        ImmutableMap<String, GrpcStatusCode> errors) {
      super(message, cause);
      this.streamName = streamName;
      this.errors = errors;
    }

    public ImmutableMap<String, GrpcStatusCode> getErrors() {
      return errors;
    }

    public String getStreamName() {
      return streamName;
    }
  }

  /** Stream has already been finalized. */
  public static final class StreamFinalizedException extends StorageException {
    protected StreamFinalizedException(String name, String message, Throwable cause) {
      super(message, cause, name, ImmutableMap.of());
    }
  }

  /**
   * There was a schema mismatch due to bigquery table with fewer fields than the input message.
   * This can be resolved by updating the table's schema with the message schema.
   */
  public static final class SchemaMismatchedException extends StorageException {
    protected SchemaMismatchedException(String name, String message, Throwable cause) {
      super(message, cause, name, ImmutableMap.of());
    }
  }

  private static StorageError toStorageError(com.google.rpc.Status rpcStatus) {
    for (Any detail : rpcStatus.getDetailsList()) {
      if (detail.is(StorageError.class)) {
        try {
          return detail.unpack(StorageError.class);
        } catch (InvalidProtocolBufferException protoException) {
          throw new IllegalStateException(protoException);
        }
      }
    }
    return null;
  }

  /**
   * Converts a c.g.rpc.Status into a StorageException, if possible. Examines the embedded
   * StorageError, and potentially returns a {@link StreamFinalizedException} or {@link
   * SchemaMismatchedException} (both derive from StorageException). If there is no StorageError, or
   * the StorageError is a different error it will return NULL.
   */
  @Nullable
  public static StorageException toStorageException(
      com.google.rpc.Status rpcStatus, Throwable exception) {
    StorageError error = toStorageError(rpcStatus);
    if (error == null) {
      return null;
    }
    switch (error.getCode()) {
      case STREAM_FINALIZED:
        return new StreamFinalizedException(error.getEntity(), error.getErrorMessage(), exception);

      case SCHEMA_MISMATCH_EXTRA_FIELDS:
        return new SchemaMismatchedException(error.getEntity(), error.getErrorMessage(), exception);

      default:
        return null;
    }
  }

  /**
   * Converts a Throwable into a StorageException, if possible. Examines the embedded error message,
   * and potentially returns a {@link StreamFinalizedException} or {@link SchemaMismatchedException}
   * (both derive from StorageException). If there is no StorageError, or the StorageError is a
   * different error it will return NULL.
   */
  @Nullable
  public static StorageException toStorageException(Throwable exception) {
    // TODO: switch to using rpcStatus when cl/408735437 is rolled out
    // com.google.rpc.Status rpcStatus = StatusProto.fromThrowable(exception);
    Status grpcStatus = Status.fromThrowable(exception);
    String message = exception.getMessage();
    String streamPatternString = "projects/[^/]+/datasets/[^/]+/tables/[^/]+/streams/[^/]+";
    Pattern streamPattern = Pattern.compile(streamPatternString);
    if (message == null) {
      return null;
    }
    // TODO: SWTICH TO CHECK SCHEMA_MISMATCH_EXTRA_FIELDS IN THE ERROR CODE
    if (grpcStatus.getCode().equals(Code.INVALID_ARGUMENT)
        && message.toLowerCase().contains("input schema has more fields than bigquery schema")) {
      Matcher streamMatcher = streamPattern.matcher(message);
      String entity = streamMatcher.find() ? streamMatcher.group() : "streamName unkown";
      return new SchemaMismatchedException(entity, message, exception);
    }
    // TODO: SWTICH TO CHECK STREAM_FINALIZED IN THE ERROR CODE
    if (grpcStatus.getCode().equals(Code.INVALID_ARGUMENT)
        && message.toLowerCase().contains("stream has been finalized and cannot be appended")) {
      Matcher streamMatcher = streamPattern.matcher(message);
      String entity = streamMatcher.find() ? streamMatcher.group() : "streamName unkown";
      return new StreamFinalizedException(entity, message, exception);
    }
    return null;
  }

  private Exceptions() {}
}
