package com.amazonaws.athena.connector.lambda.handlers;

/*-
 * #%L
 * Amazon Athena Query Federation SDK
 * %%
 * Copyright (C) 2019 Amazon Web Services
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import com.amazonaws.athena.connector.lambda.data.BlockAllocator;
import com.amazonaws.athena.connector.lambda.data.BlockAllocatorImpl;
import com.amazonaws.athena.connector.lambda.data.BlockUtils;
import com.amazonaws.athena.connector.lambda.data.SchemaBuilder;
import com.amazonaws.athena.connector.lambda.domain.Split;
import com.amazonaws.athena.connector.lambda.domain.TableName;
import com.amazonaws.athena.connector.lambda.domain.predicate.Constraints;
import com.amazonaws.athena.connector.lambda.domain.spill.S3SpillLocation;
import com.amazonaws.athena.connector.lambda.domain.spill.SpillLocationVerifier;
import com.amazonaws.athena.connector.lambda.exceptions.AthenaConnectorException;
import com.amazonaws.athena.connector.lambda.metadata.GetSplitsRequest;
import com.amazonaws.athena.connector.lambda.metadata.GetSplitsResponse;
import com.amazonaws.athena.connector.lambda.metadata.GetTableLayoutRequest;
import com.amazonaws.athena.connector.lambda.metadata.GetTableLayoutResponse;
import com.amazonaws.athena.connector.lambda.metadata.GetTableRequest;
import com.amazonaws.athena.connector.lambda.metadata.GetTableResponse;
import com.amazonaws.athena.connector.lambda.metadata.ListSchemasRequest;
import com.amazonaws.athena.connector.lambda.metadata.ListSchemasResponse;
import com.amazonaws.athena.connector.lambda.metadata.ListTablesRequest;
import com.amazonaws.athena.connector.lambda.metadata.ListTablesResponse;
import com.amazonaws.athena.connector.lambda.metadata.MetadataRequestType;
import com.amazonaws.athena.connector.lambda.records.ReadRecordsRequest;
import com.amazonaws.athena.connector.lambda.records.ReadRecordsResponse;
import com.amazonaws.athena.connector.lambda.request.FederationRequest;
import com.amazonaws.athena.connector.lambda.request.PingRequest;
import com.amazonaws.athena.connector.lambda.request.PingResponse;
import com.amazonaws.athena.connector.lambda.security.FederatedIdentity;
import com.amazonaws.athena.connector.lambda.security.IdentityUtil;
import com.amazonaws.athena.connector.lambda.serde.ObjectMapperFactory;
import com.amazonaws.services.lambda.runtime.Context;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.arrow.vector.types.Types;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.glue.model.FederationSourceErrorCode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.UUID;

import static com.amazonaws.athena.connector.lambda.domain.predicate.Constraints.DEFAULT_NO_LIMIT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CompositeHandlerTest {
    private static final Logger logger = LoggerFactory.getLogger(CompositeHandlerTest.class);

    private MetadataHandler mockMetadataHandler;
    private RecordHandler mockRecordHandler;
    private UserDefinedFunctionHandler mockUserDefinedFunctionHandler;
    private CompositeHandler compositeHandler;
    private BlockAllocatorImpl allocator;
    private ObjectMapper objectMapper;
    private Schema schemaForRead;

    @Rule
    public TestName testName = new TestName();

    @Before
    public void setUp()
            throws Exception {
        logger.info("{}: enter", testName.getMethodName());

        allocator = new BlockAllocatorImpl();
        objectMapper = ObjectMapperFactory.create(allocator);
        mockMetadataHandler = mock(MetadataHandler.class);
        mockRecordHandler = mock(RecordHandler.class);
        mockUserDefinedFunctionHandler = mock(UserDefinedFunctionHandler.class);

        schemaForRead = SchemaBuilder.newBuilder()
                .addField("col1", new ArrowType.Int(32, true))
                .build();

        when(mockMetadataHandler.doGetTableLayout(nullable(BlockAllocatorImpl.class), nullable(GetTableLayoutRequest.class)))
                .thenReturn(new GetTableLayoutResponse("catalog",
                        new TableName("schema", "table"),
                        BlockUtils.newBlock(allocator, "col1", Types.MinorType.BIGINT.getType(), 1L)));

        when(mockMetadataHandler.doListTables(nullable(BlockAllocatorImpl.class), nullable(ListTablesRequest.class)))
                .thenReturn(new ListTablesResponse("catalog",
                        Collections.singletonList(new TableName("schema", "table")), null));

        when(mockMetadataHandler.doGetTable(nullable(BlockAllocatorImpl.class), nullable(GetTableRequest.class)))
                .thenReturn(new GetTableResponse("catalog",
                        new TableName("schema", "table"),
                        SchemaBuilder.newBuilder().addStringField("col1").build()));

        when(mockMetadataHandler.doListSchemaNames(nullable(BlockAllocatorImpl.class), nullable(ListSchemasRequest.class)))
                .thenReturn(new ListSchemasResponse("catalog", Collections.singleton("schema1")));

        when(mockMetadataHandler.doGetSplits(nullable(BlockAllocatorImpl.class), nullable(GetSplitsRequest.class)))
                .thenReturn(new GetSplitsResponse("catalog", Split.newBuilder(null, null).build()));

        when(mockMetadataHandler.doPing(nullable(PingRequest.class)))
                .thenReturn(new PingResponse("catalog", "queryId", "type", 23, 2));

        when(mockRecordHandler.doReadRecords(nullable(BlockAllocatorImpl.class), nullable(ReadRecordsRequest.class)))
                .thenReturn(new ReadRecordsResponse("catalog",
                        BlockUtils.newEmptyBlock(allocator, "col", new ArrowType.Int(32, true))));

        compositeHandler = new CompositeHandler(mockMetadataHandler, mockRecordHandler);
    }

    @After
    public void after() {
        allocator.close();
        logger.info("{}: exit ", testName.getMethodName());
    }

    @Test
    public void doReadRecords()
            throws Exception {
        ReadRecordsRequest req = new ReadRecordsRequest(IdentityUtil.fakeIdentity(),
                "catalog",
                "queryId-" + System.currentTimeMillis(),
                new TableName("schema", "table"),
                schemaForRead,
                Split.newBuilder(S3SpillLocation.newBuilder()
                        .withBucket("athena-virtuoso-test")
                        .withPrefix("lambda-spill")
                        .withQueryId(UUID.randomUUID().toString())
                        .withSplitId(UUID.randomUUID().toString())
                        .withIsDirectory(true)
                        .build(), null).build(),
                new Constraints(new HashMap<>(), Collections.emptyList(), Collections.emptyList(), DEFAULT_NO_LIMIT, Collections.emptyMap(), null),
                100_000_000_000L, //100GB don't expect this to spill
                100_000_000_000L
        );
        compositeHandler.handleRequest(allocator, req, new ByteArrayOutputStream(), objectMapper);
        verify(mockRecordHandler, times(1))
                .doReadRecords(nullable(BlockAllocator.class), nullable(ReadRecordsRequest.class));
    }

    @Test
    public void doListSchemaNames()
            throws Exception {
        ListSchemasRequest req = mock(ListSchemasRequest.class);
        when(req.getRequestType()).thenReturn(MetadataRequestType.LIST_SCHEMAS);
        compositeHandler.handleRequest(allocator, req, new ByteArrayOutputStream(), objectMapper);
        verify(mockMetadataHandler, times(1)).doListSchemaNames(nullable(BlockAllocatorImpl.class), nullable(ListSchemasRequest.class));
    }

    @Test
    public void doListTables()
            throws Exception {
        ListTablesRequest req = mock(ListTablesRequest.class);
        when(req.getRequestType()).thenReturn(MetadataRequestType.LIST_TABLES);
        compositeHandler.handleRequest(allocator, req, new ByteArrayOutputStream(), objectMapper);
        verify(mockMetadataHandler, times(1)).doListTables(nullable(BlockAllocatorImpl.class), nullable(ListTablesRequest.class));
    }

    @Test
    public void doGetTable()
            throws Exception {
        GetTableRequest req = mock(GetTableRequest.class);
        when(req.getRequestType()).thenReturn(MetadataRequestType.GET_TABLE);
        compositeHandler.handleRequest(allocator, req, new ByteArrayOutputStream(), objectMapper);
        verify(mockMetadataHandler, times(1)).doGetTable(nullable(BlockAllocatorImpl.class), nullable(GetTableRequest.class));
    }

    @Test
    public void doGetTableLayout()
            throws Exception {
        GetTableLayoutRequest req = mock(GetTableLayoutRequest.class);
        when(req.getRequestType()).thenReturn(MetadataRequestType.GET_TABLE_LAYOUT);
        compositeHandler.handleRequest(allocator, req, new ByteArrayOutputStream(), objectMapper);
        verify(mockMetadataHandler, times(1)).doGetTableLayout(nullable(BlockAllocatorImpl.class), nullable(GetTableLayoutRequest.class));
    }

    @Test
    public void doGetSplits()
            throws Exception {
        FederatedIdentity federatedIdentity = IdentityUtil.fakeIdentity();
        GetSplitsRequest req = mock(GetSplitsRequest.class);
        when(req.getRequestType()).thenReturn(MetadataRequestType.GET_SPLITS);
        when(req.getIdentity()).thenReturn(federatedIdentity);
        SpillLocationVerifier mockVerifier = mock(SpillLocationVerifier.class);
        doNothing().when(mockVerifier).checkBucketAuthZ(nullable(String.class));
        FieldUtils.writeField(mockMetadataHandler, "verifier", mockVerifier, true);
        compositeHandler.handleRequest(allocator, req, new ByteArrayOutputStream(), objectMapper);
        verify(mockMetadataHandler, times(1)).doGetSplits(nullable(BlockAllocatorImpl.class), nullable(GetSplitsRequest.class));
    }

    @Test
    public void doPing()
            throws Exception {
        PingRequest req = mock(PingRequest.class);
        when(req.getCatalogName()).thenReturn("catalog");
        when(req.getQueryId()).thenReturn("queryId");
        compositeHandler.handleRequest(allocator, req, new ByteArrayOutputStream(), objectMapper);
        verify(mockMetadataHandler, times(1)).doPing(nullable(PingRequest.class));
    }

    @Test
    public void handleRequestWithValidInput() throws Exception {
        String requestData = "{\n" +
                "          \"@type\": \"ListTablesRequest\",\n" +
                "          \"identity\": {\n" +
                "            \"id\": \"UNKNOWN\",\n" +
                "            \"principal\": \"UNKNOWN\",\n" +
                "            \"account\": \"12345678910\",\n" +
                "            \"arn\": \"arn:aws:iam::12345678910:user/test@test.com\",\n" +
                "            \"tags\": {},\n" +
                "            \"groups\": []\n" +
                "          },\n" +
                "          \"queryId\": \"auto-generated-by-athena\",\n" +
                "          \"catalogName\": \"testCatalog\",\n" +
                "          \"schemaName\": \"testSchema\",\n" +
                "          \"nextToken\": \"testTable\",\n" +
                "          \"pageSize\": 5\n" +
                "        }";

        InputStream inputStream = new ByteArrayInputStream(requestData.getBytes());
        OutputStream outputStream = new ByteArrayOutputStream();
        Context mockContext = mock(Context.class);

        FederatedIdentity identity = new FederatedIdentity(
                "arn:aws:iam::12345678910:user/test@test.com",
                "12345678910",
                Collections.emptyMap(),
                Collections.emptyList(),
                Collections.emptyMap()
        );
        ListTablesRequest expectedRequest = new ListTablesRequest(
                identity,
                "auto-generated-by-athena",
                "testCatalog",
                "testSchema",
                "testTable",
                5
        );

        when(mockMetadataHandler.doListTables(any(BlockAllocator.class), eq(expectedRequest)))
                .thenReturn(new ListTablesResponse(
                        "testCatalog",
                        Collections.singletonList(new TableName("testSchema", "testTable")),
                        null
                ));

        compositeHandler.handleRequest(inputStream, outputStream, mockContext);

        verify(mockMetadataHandler, times(1))
                .doListTables(any(BlockAllocator.class), eq(expectedRequest));

        String outputData = outputStream.toString();
        assertNotNull(outputData);
        assertTrue(outputData.contains("\"tables\":[{\"schemaName\":\"testSchema\",\"tableName\":\"testTable\"}]"));
    }

    @Test
    public void handleRequestWithInvalidInput() {
        String invalidRequestData = "{ \"invalid\": \"data\" }";

        InputStream inputStream = new ByteArrayInputStream(invalidRequestData.getBytes());
        OutputStream outputStream = new ByteArrayOutputStream();
        Context mockContext = mock(Context.class);

        Exception exception = assertThrows(RuntimeException.class, () ->
                compositeHandler.handleRequest(inputStream, outputStream, mockContext)
        );

        String exceptionMessage = exception.getCause() != null ? exception.getCause().getMessage() : exception.getMessage();
        assertNotNull(exceptionMessage);

        assertTrue(exceptionMessage.contains("Could not resolve subtype of"));
    }

    @Test
    public void handleRequest_withUnknownRequest_throwsInvalidInputException() {
        FederationRequest unknownRequest = new FederationRequest() {
            @Override
            public void close() {
                // no-op
            }
        };

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        AthenaConnectorException exception = assertThrows(AthenaConnectorException.class, () ->
                compositeHandler.handleRequest(allocator, unknownRequest, outputStream, objectMapper)
        );
        assertTrue(exception.getMessage().contains("Unknown request class"));
        assertEquals(FederationSourceErrorCode.INVALID_INPUT_EXCEPTION.toString(),
                exception.getErrorDetails().errorCode());
        assertEquals(0, outputStream.size());
    }
}
