/*
 * Copyright 2019 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"; you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.topic;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.hamcrest.MockitoHamcrest.argThat;

import com.google.common.collect.ImmutableMap;
import io.confluent.ksql.function.InternalFunctionRegistry;
import io.confluent.ksql.metastore.MetaStoreImpl;
import io.confluent.ksql.metastore.MutableMetaStore;
import io.confluent.ksql.metastore.model.KeyField;
import io.confluent.ksql.metastore.model.KsqlStream;
import io.confluent.ksql.metastore.model.KsqlTopic;
import io.confluent.ksql.parser.DefaultKsqlParser;
import io.confluent.ksql.parser.KsqlParser;
import io.confluent.ksql.parser.KsqlParser.PreparedStatement;
import io.confluent.ksql.parser.properties.with.CreateSourceAsProperties;
import io.confluent.ksql.parser.properties.with.CreateSourceProperties;
import io.confluent.ksql.parser.tree.CreateAsSelect;
import io.confluent.ksql.parser.tree.CreateSource;
import io.confluent.ksql.schema.ksql.LogicalSchema;
import io.confluent.ksql.serde.Format;
import io.confluent.ksql.serde.FormatInfo;
import io.confluent.ksql.serde.KeyFormat;
import io.confluent.ksql.serde.SerdeOption;
import io.confluent.ksql.serde.ValueFormat;
import io.confluent.ksql.services.KafkaTopicClient;
import io.confluent.ksql.statement.ConfiguredStatement;
import io.confluent.ksql.util.KsqlConfig;
import io.confluent.ksql.util.KsqlException;
import io.confluent.ksql.util.timestamp.MetadataTimestampExtractionPolicy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class TopicCreateInjectorTest {

  private static final LogicalSchema SCHEMA = LogicalSchema.of(SchemaBuilder
      .struct()
      .field("F1", Schema.OPTIONAL_STRING_SCHEMA)
      .build());

  @Rule
  public final ExpectedException expectedException = ExpectedException.none();

  @Mock
  private TopicProperties.Builder builder;
  @Mock
  private KafkaTopicClient topicClient;
  @Mock
  private TopicDescription sourceDescription;

  private KsqlParser parser;
  private MutableMetaStore metaStore;
  private TopicCreateInjector injector;
  private Map<String, Object> overrides;
  private ConfiguredStatement<?> statement;
  private KsqlConfig config;

  @Before
  public void setUp() {
    parser = new DefaultKsqlParser();
    metaStore = new MetaStoreImpl(new InternalFunctionRegistry());
    overrides = new HashMap<>();
    config = new KsqlConfig(new HashMap<>());

    injector = new TopicCreateInjector(topicClient, metaStore);

    final KsqlTopic sourceTopic = new KsqlTopic(
        "source",
        KeyFormat.nonWindowed(FormatInfo.of(Format.KAFKA)),
        ValueFormat.of(FormatInfo.of(Format.JSON)),
        false
    );

    final KsqlStream source = new KsqlStream<>(
        "",
        "SOURCE",
        SCHEMA,
        SerdeOption.none(),
        KeyField.none(),
        new MetadataTimestampExtractionPolicy(),
        sourceTopic
    );
    metaStore.putSource(source);

    final KsqlTopic joinTopic = new KsqlTopic(
        "jSource",
        KeyFormat.nonWindowed(FormatInfo.of(Format.KAFKA)),
        ValueFormat.of(FormatInfo.of(Format.JSON)),
        false
    );

    final KsqlStream joinSource = new KsqlStream<>(
        "",
        "J_SOURCE",
        SCHEMA,
        SerdeOption.none(),
        KeyField.none(),
        new MetadataTimestampExtractionPolicy(),
        joinTopic
    );
    metaStore.putSource(joinSource);

    when(topicClient.describeTopic("source")).thenReturn(sourceDescription);
    when(topicClient.isTopicExists("source")).thenReturn(true);
    when(builder.withName(any())).thenReturn(builder);
    when(builder.withWithClause(any(), any(), any())).thenReturn(builder);
    when(builder.withOverrides(any())).thenReturn(builder);
    when(builder.withKsqlConfig(any())).thenReturn(builder);
    when(builder.withSource(any())).thenReturn(builder);
    when(builder.build()).thenReturn(new TopicProperties("name", 1, (short) 1));
  }

  @Test
  public void shouldDoNothingForNonCAS() {
    // Given:
    final ConfiguredStatement<?> statement = givenStatement("LIST PROPERTIES;");

    // When:
    final ConfiguredStatement<?> result = injector.inject(statement);

    // Then:
    assertThat(result, is(sameInstance(statement)));
  }

  @Test
  public void shouldGenerateName() {
    // Given:
    givenStatement("CREATE STREAM x AS SELECT * FROM SOURCE;");

    // When:
    injector.inject(statement, builder);

    // Then:
    verify(builder).withName("X");
  }

  @Test
  public void shouldUseNameFromCreate() {
    // Given:
    givenStatement("CREATE STREAM x (FOO VARCHAR) WITH (value_format='avro', kafka_topic='foo', partitions=1);");

    // When:
    injector.inject(statement, builder);

    // Then:
    verify(builder).withName("foo");
  }

  @Test
  public void shouldGenerateNameWithCorrectPrefixFromOverrides() {
    // Given:
    givenStatement("CREATE STREAM x AS SELECT * FROM SOURCE;");
    overrides.put(KsqlConfig.KSQL_OUTPUT_TOPIC_NAME_PREFIX_CONFIG, "prefix-");
    config = new KsqlConfig(ImmutableMap.of(
        KsqlConfig.KSQL_OUTPUT_TOPIC_NAME_PREFIX_CONFIG, "nope"
    ));

    // When:
    injector.inject(statement, builder);

    // Then:
    verify(builder).withName("prefix-X");
  }

  @Test
  public void shouldGenerateNameWithCorrectPrefixFromConfig() {
    // Given:
    givenStatement("CREATE STREAM x AS SELECT * FROM SOURCE;");
    config = new KsqlConfig(ImmutableMap.of(
        KsqlConfig.KSQL_OUTPUT_TOPIC_NAME_PREFIX_CONFIG, "prefix-"
    ));

    // When:
    injector.inject(statement.withConfig(config), builder);

    // Then:
    verify(builder).withName("prefix-X");
  }

  @Test
  public void shouldPassThroughWithClauseToBuilderForCreateAs() {
    // Given:
    givenStatement("CREATE STREAM x WITH (kafka_topic='topic') AS SELECT * FROM SOURCE;");

    final CreateSourceAsProperties props = ((CreateAsSelect) statement.getStatement())
        .getProperties();

    // When:
    injector.inject(statement, builder);

    // Then:
    verify(builder).withWithClause(
        props.getKafkaTopic(),
        props.getPartitions(),
        props.getReplicas()
    );
  }

  @Test
  public void shouldPassThroughWithClauseToBuilderForCreate() {
    // Given:
    givenStatement("CREATE STREAM x (FOO VARCHAR) WITH(value_format='avro', kafka_topic='topic', partitions=2);");

    final CreateSourceProperties props = ((CreateSource) statement.getStatement())
        .getProperties();

    // When:
    injector.inject(statement, builder);

    // Then:

    verify(builder).withWithClause(
        Optional.of(props.getKafkaTopic()),
        props.getPartitions(),
        props.getReplicas()
    );
  }

  @Test
  public void shouldPassThroughOverridesToBuilder() {
    // Given:
    givenStatement("CREATE STREAM x WITH (kafka_topic='topic') AS SELECT * FROM SOURCE;");

    // When:
    injector.inject(statement, builder);

    // Then:
    verify(builder).withOverrides(overrides);
  }

  @Test
  public void shouldPassThroughOverridesToBuilderForCreate() {
    // Given:
    givenStatement("CREATE STREAM x (FOO VARCHAR) WITH(value_format='avro', kafka_topic='topic', partitions=2);");

    // When:
    injector.inject(statement, builder);

    // Then:
    verify(builder).withOverrides(overrides);
  }

  @Test
  public void shouldPassThroughConfigToBuilder() {
    // Given:
    givenStatement("CREATE STREAM x WITH (kafka_topic='topic') AS SELECT * FROM SOURCE;");

    // When:
    injector.inject(statement, builder);

    // Then:
    verify(builder).withKsqlConfig(config);
  }

  @Test
  public void shouldPassThroughConfigToBuilderForCreate() {
    // Given:
    givenStatement("CREATE STREAM x (FOO VARCHAR) WITH(value_format='avro', kafka_topic='topic', partitions=2);");

    // When:
    injector.inject(statement, builder);

    // Then:
    verify(builder).withKsqlConfig(config);
  }

  @Test
  public void shouldNotUseSourceTopicForCreateMissingTopic() {
    // Given:
    givenStatement("CREATE STREAM x (FOO VARCHAR) WITH(value_format='avro', kafka_topic='topic', partitions=2);");

    // When:
    injector.inject(statement, builder);

    // Then:
    verify(builder, never()).withSource(any());
  }

  @Test
  public void shouldUseSourceTopicForCreateExistingTopic() {
    // Given:
    givenStatement("CREATE STREAM x (FOO VARCHAR) WITH(value_format='avro', kafka_topic='source', partitions=2);");

    // When:
    injector.inject(statement, builder);

    // Then:
    verify(builder).withSource(argThat(supplierThatGets(sourceDescription)));
  }

  @Test
  public void shouldIdentifyAndUseCorrectSource() {
    // Given:
    givenStatement("CREATE STREAM x WITH (kafka_topic='topic') AS SELECT * FROM SOURCE;");

    // When:
    injector.inject(statement, builder);

    // Then:
    verify(builder).withSource(argThat(supplierThatGets(sourceDescription)));
  }

  @Test
  public void shouldIdentifyAndUseCorrectSourceInJoin() {
    // Given:
    givenStatement("CREATE STREAM x WITH (kafka_topic='topic') AS SELECT * FROM SOURCE "
        + "JOIN J_SOURCE ON SOURCE.X = J_SOURCE.X;");

    // When:
    injector.inject(statement, builder);

    // Then:
    verify(builder).withSource(argThat(supplierThatGets(sourceDescription)));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void shouldBuildWithClauseWithTopicProperties() {
    // Given:
    givenStatement("CREATE STREAM x WITH (kafka_topic='topic') AS SELECT * FROM SOURCE;");

    when(builder.build()).thenReturn(new TopicProperties("expectedName", 10, (short) 10));

    // When:
    final ConfiguredStatement<CreateAsSelect> result =
        (ConfiguredStatement<CreateAsSelect>) injector.inject(statement, builder);

    // Then:
    final CreateSourceAsProperties props = result.getStatement().getProperties();
    assertThat(props.getKafkaTopic(), is(Optional.of("expectedName")));
    assertThat(props.getPartitions(), is(Optional.of(10)));
    assertThat(props.getReplicas(), is(Optional.of((short)10)));
  }

  @Test
  public void shouldUpdateStatementText() {
    // Given:
    givenStatement("CREATE STREAM x AS SELECT * FROM SOURCE;");

    // When:
    final ConfiguredStatement<?> result = injector.inject(statement, builder);

    // Then:
    assertThat(result.getStatementText(),
        equalTo(
            "CREATE STREAM X WITH (KAFKA_TOPIC='name', PARTITIONS=1, REPLICAS=1) AS SELECT *"
                + "\nFROM SOURCE SOURCE;"));
  }

  @Test
  public void shouldCreateMissingTopic() {
    // Given:
    givenStatement("CREATE STREAM x WITH (kafka_topic='topic') AS SELECT * FROM SOURCE;");
    when(builder.build()).thenReturn(new TopicProperties("expectedName", 10, (short) 10));

    // When:
    injector.inject(statement, builder);

    // Then:
    verify(topicClient).createTopic(
        "expectedName",
        10,
        (short) 10,
        ImmutableMap.of());
  }

  @Test
  public void shouldCreateMissingTopicForCreate() {
    // Given:
    givenStatement("CREATE STREAM x WITH (kafka_topic='topic') AS SELECT * FROM SOURCE;");
    when(builder.build()).thenReturn(new TopicProperties("expectedName", 10, (short) 10));

    // When:
    injector.inject(statement, builder);

    // Then:
    verify(topicClient).createTopic(
        "expectedName",
        10,
        (short) 10,
        ImmutableMap.of());
  }

  @Test
  public void shouldCreateMissingTopicWithCompactCleanupPolicyForNonWindowedTables() {
    // Given:
    givenStatement("CREATE TABLE x WITH (kafka_topic='topic') "
        + "AS SELECT * FROM SOURCE;");
    when(builder.build()).thenReturn(new TopicProperties("expectedName", 10, (short) 10));

    // When:
    injector.inject(statement, builder);

    // Then:
    verify(topicClient).createTopic(
        "expectedName",
        10,
        (short) 10,
        ImmutableMap.of(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT));
  }

  @Test
  public void shouldCreateMissingTopicWithCompactCleanupPolicyForCreateTable() {
    // Given:
    givenStatement("CREATE TABLE foo (FOO VARCHAR) WITH (value_format='avro', kafka_topic='topic', partitions=1);");
    when(builder.build()).thenReturn(new TopicProperties("topic", 10, (short) 10));

    // When:
    injector.inject(statement, builder);

    // Then:
    verify(topicClient).createTopic(
        "topic",
        10,
        (short) 10,
        ImmutableMap.of(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT));
  }

  @Test
  public void shouldCreateMissingTopicWithDefaultCleanupPolicyForWindowedTables() {
    // Given:
    givenStatement("CREATE TABLE x WITH (kafka_topic='topic') "
        + "AS SELECT * FROM SOURCE WINDOW TUMBLING (SIZE 10 SECONDS);");
    when(builder.build()).thenReturn(new TopicProperties("expectedName", 10, (short) 10));

    // When:
    injector.inject(statement, builder);

    // Then:
    verify(topicClient).createTopic(
        "expectedName",
        10,
        (short) 10,
        ImmutableMap.of());
  }

  @Test
  public void shouldHaveSuperUsefulErrorMessageIfCreateWithNoPartitions() {
    // Given:
    givenStatement("CREATE STREAM foo (FOO STRING) WITH (value_format='avro', kafka_topic='doesntexist');");

    // Expect:
    expectedException.expect(KsqlException.class);
    expectedException.expectMessage(
        "Topic 'doesntexist' does not exist. If you want to create a new topic for the "
            + "stream/table please re-run the statement providing the required 'PARTITIONS' "
            + "configuration in the WITH clause (and optionally 'REPLICAS'). For example: "
            + "CREATE STREAM FOO (FOO STRING) "
            + "WITH (KAFKA_TOPIC='doesntexist', PARTITIONS=2, REPLICAS=1, VALUE_FORMAT='avro');");

    // When:
    injector.inject(statement, builder);
  }

  private ConfiguredStatement<?> givenStatement(final String sql) {
    final PreparedStatement<?> preparedStatement =
        parser.prepare(parser.parse(sql).get(0), metaStore);
    final ConfiguredStatement<?> configuredStatement =
        ConfiguredStatement.of(
            preparedStatement,
            overrides,
            config);
    statement = configuredStatement;
    return configuredStatement;
  }

  private static TypeSafeMatcher<Supplier<TopicDescription>> supplierThatGets(
      final TopicDescription topicDescription) {
    return new TypeSafeMatcher<Supplier<TopicDescription>>() {
      @Override
      protected boolean matchesSafely(final Supplier<TopicDescription> item) {
        return item.get().equals(topicDescription);
      }

      @Override
      public void describeTo(final Description description) {
        description.appendText(topicDescription.toString());
      }
    };
  }

}
