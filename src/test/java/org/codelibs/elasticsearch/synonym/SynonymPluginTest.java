package org.codelibs.elasticsearch.synonym;

import static org.codelibs.elasticsearch.runner.ElasticsearchClusterRunner.*;
import static org.junit.Assert.*;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;

import org.codelibs.elasticsearch.runner.ElasticsearchClusterRunner;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.ImmutableSettings.Builder;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.query.MatchQueryBuilder.Type;
import org.elasticsearch.index.query.QueryBuilders;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SynonymPluginTest {

    private ElasticsearchClusterRunner runner;

    private File[] synonymFiles;

    private int numOfNode = 1;

    private int numOfDocs = 1000;

    @Before
    public void setUp() throws Exception {
        runner = new ElasticsearchClusterRunner();
        runner.onBuild(new ElasticsearchClusterRunner.Builder() {
            @Override
            public void build(final int number, final Builder settingsBuilder) {
                settingsBuilder.put("http.cors.enabled", true);
                settingsBuilder.put("index.number_of_replicas", 0);
                settingsBuilder.put("index.number_of_shards", 1);
            }
        }).build(newConfigs().ramIndexStore().numOfNode(numOfNode));

        synonymFiles = null;
    }

    private void updateDictionary(File file, String content)
            throws IOException, UnsupportedEncodingException,
            FileNotFoundException {
        try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file), "UTF-8"))) {
            bw.write(content);
            bw.flush();
        }
    }

    @After
    public void cleanUp() throws Exception {
        runner.close();
        runner.clean();
        if (synonymFiles != null) {
            for (File file : synonymFiles) {
                file.deleteOnExit();
            }
        }
    }

    @Test
    public void test_synonyms() throws Exception {

        runner.ensureYellow();

        final String index = "dataset";
        final String type = "item";

        final String indexSettings = "{\"index\":{\"analysis\":{"
                + "\"tokenizer\":{"//
                + "\"2gram\":{\"type\":\"nGram\",\"min_gram\":\"2\",\"max_gram\":\"2\",\"token_chars\":[\"letter\",\"digit\"]},"
                + "\"2gram_synonym\":{\"type\":\"ngram_synonym\",\"n\":\"2\",\"synonyms\":[\"あ,かき,さしす,たちつて,なにぬねの\",\"東京,とうきょう\"]}"
                + "},"//
                + "\"filter\":{"//
                + "\"synonym\":{\"type\":\"synonym\",\"synonyms\":[\"あ,かき,さしす,たちつて,なにぬねの\",\"東京,とうきょう\"]}"
                + "},"//
                + "\"analyzer\":{"
                + "\"2gram_analyzer\":{\"type\":\"custom\",\"tokenizer\":\"2gram\",\"filter\":[\"synonym\"]},"
                + "\"2gram_synonym_analyzer\":{\"type\":\"custom\",\"tokenizer\":\"2gram_synonym\"}"
                + "}"//
                + "}}}";
        runner.createIndex(index,
                ImmutableSettings.builder().loadFromSource(indexSettings)
                        .build());

        // create a mapping
        final XContentBuilder mappingBuilder = XContentFactory.jsonBuilder()//
                .startObject()//
                .startObject(type)//
                .startObject("properties")//

                // id
                .startObject("id")//
                .field("type", "string")//
                .field("index", "not_analyzed")//
                .endObject()//

                // msg1
                .startObject("msg1")//
                .field("type", "string")//
                .field("analyzer", "2gram_synonym_analyzer")//
                .endObject()//

                // msg2
                .startObject("msg2")//
                .field("type", "string")//
                .field("analyzer", "2gram_analyzer")//
                .endObject()//

                .endObject()//
                .endObject()//
                .endObject();
        runner.createMapping(index, type, mappingBuilder);

        for (int i = 1; i <= numOfDocs; i++) {
            final IndexResponse indexResponse1 = runner.insert(index, type,
                    String.valueOf(i),
                    "{\"msg1\":\"あいうえお\", \"msg2\":\"あいうえお\", \"id\":\"" + i
                            + "\"}");
            assertTrue(indexResponse1.isCreated());
        }
        runner.refresh();

        // 1000ドキュメント作成されたか確認
        {
            final SearchResponse searchResponse = runner.search(index, type,
                    null, null, 0, 10);
            assertEquals(numOfDocs, searchResponse.getHits().getTotalHits());
        }

        assertDocCount(numOfDocs, index, type, "msg1", "あ");
        assertDocCount(numOfDocs, index, type, "msg1", "あい");
        assertDocCount(numOfDocs, index, type, "msg1", "あいう");
        assertDocCount(numOfDocs, index, type, "msg1", "あいうえ");
        assertDocCount(numOfDocs, index, type, "msg1", "あいうえお");

        assertDocCount(0, index, type, "msg1", "かいうえお");
        assertDocCount(numOfDocs, index, type, "msg1", "かきいうえお");
        assertDocCount(numOfDocs, index, type, "msg1", "かきいうえ");
        assertDocCount(numOfDocs, index, type, "msg1", "かきいう");
        assertDocCount(numOfDocs, index, type, "msg1", "かきい");
        assertDocCount(numOfDocs, index, type, "msg1", "かき");
        assertDocCount(0, index, type, "msg1", "か");

        assertDocCount(0, index, type, "msg2", "あ");
        assertDocCount(numOfDocs, index, type, "msg2", "あい");
        assertDocCount(numOfDocs, index, type, "msg2", "あいう");
        assertDocCount(numOfDocs, index, type, "msg2", "あいうえ");
        assertDocCount(numOfDocs, index, type, "msg2", "あいうえお");
        assertDocCount(0, index, type, "msg2", "か");
        assertDocCount(0, index, type, "msg2", "かき");
    }

    @Test
    public void test_synonymPath() throws Exception {
        synonymFiles = new File[numOfNode];
        for (int i = 0; i < numOfNode; i++) {
            String confPath = runner.getNode(i).settings().get("path.conf");
            synonymFiles[i] = new File(confPath, "synonym.txt");
            updateDictionary(synonymFiles[i], "あ,かき,さしす,たちつて,なにぬねの\n東京,とうきょう");
        }

        runner.ensureYellow();

        final String index = "dataset";
        final String type = "item";

        final String indexSettings = "{\"index\":{\"analysis\":{"
                + "\"tokenizer\":{"//
                + "\"2gram\":{\"type\":\"nGram\",\"min_gram\":\"2\",\"max_gram\":\"2\",\"token_chars\":[\"letter\",\"digit\"]},"
                + "\"2gram_synonym\":{\"type\":\"ngram_synonym\",\"n\":\"2\",\"synonyms_path\":\"synonym.txt\"}"
                + "},"//
                + "\"filter\":{"//
                + "\"synonym\":{\"type\":\"synonym\",\"synonyms_path\":\"synonym.txt\"}"
                + "},"//
                + "\"analyzer\":{"
                + "\"2gram_analyzer\":{\"type\":\"custom\",\"tokenizer\":\"2gram\",\"filter\":[\"synonym\"]},"
                + "\"2gram_synonym_analyzer\":{\"type\":\"custom\",\"tokenizer\":\"2gram_synonym\"}"
                + "}"//
                + "}}}";
        runner.createIndex(index,
                ImmutableSettings.builder().loadFromSource(indexSettings)
                        .build());

        // create a mapping
        final XContentBuilder mappingBuilder = XContentFactory.jsonBuilder()//
                .startObject()//
                .startObject(type)//
                .startObject("properties")//

                // id
                .startObject("id")//
                .field("type", "string")//
                .field("index", "not_analyzed")//
                .endObject()//

                // msg1
                .startObject("msg1")//
                .field("type", "string")//
                .field("analyzer", "2gram_synonym_analyzer")//
                .endObject()//

                // msg2
                .startObject("msg2")//
                .field("type", "string")//
                .field("analyzer", "2gram_analyzer")//
                .endObject()//

                .endObject()//
                .endObject()//
                .endObject();
        runner.createMapping(index, type, mappingBuilder);

        for (int i = 1; i <= numOfDocs; i++) {
            final IndexResponse indexResponse1 = runner.insert(index, type,
                    String.valueOf(i),
                    "{\"msg1\":\"あいうえお\", \"msg2\":\"あいうえお\", \"id\":\"" + i
                            + "\"}");
            assertTrue(indexResponse1.isCreated());
        }
        runner.refresh();

        // 1000ドキュメント作成されたか確認
        {
            final SearchResponse searchResponse = runner.search(index, type,
                    null, null, 0, 10);
            assertEquals(numOfDocs, searchResponse.getHits().getTotalHits());
        }

        assertDocCount(numOfDocs, index, type, "msg1", "あ");
        assertDocCount(numOfDocs, index, type, "msg1", "あい");
        assertDocCount(numOfDocs, index, type, "msg1", "あいう");
        assertDocCount(numOfDocs, index, type, "msg1", "あいうえ");
        assertDocCount(numOfDocs, index, type, "msg1", "あいうえお");

        assertDocCount(0, index, type, "msg1", "かいうえお");
        assertDocCount(numOfDocs, index, type, "msg1", "かきいうえお");
        assertDocCount(numOfDocs, index, type, "msg1", "かきいうえ");
        assertDocCount(numOfDocs, index, type, "msg1", "かきいう");
        assertDocCount(numOfDocs, index, type, "msg1", "かきい");
        assertDocCount(numOfDocs, index, type, "msg1", "かき");
        assertDocCount(0, index, type, "msg1", "か");

        assertDocCount(0, index, type, "msg2", "あ");
        assertDocCount(numOfDocs, index, type, "msg2", "あい");
        assertDocCount(numOfDocs, index, type, "msg2", "あいう");
        assertDocCount(numOfDocs, index, type, "msg2", "あいうえ");
        assertDocCount(numOfDocs, index, type, "msg2", "あいうえお");
        assertDocCount(0, index, type, "msg2", "か");
        assertDocCount(0, index, type, "msg2", "かき");
    }

    @Test
    public void test_synonymPath_empty() throws Exception {
        synonymFiles = new File[numOfNode];
        for (int i = 0; i < numOfNode; i++) {
            String confPath = runner.getNode(i).settings().get("path.conf");
            synonymFiles[i] = new File(confPath, "synonym.txt");
            updateDictionary(synonymFiles[i], "");
        }

        runner.ensureYellow();

        final String index = "dataset";
        final String type = "item";

        final String indexSettings = "{\"index\":{\"analysis\":{"
                + "\"tokenizer\":{"//
                + "\"2gram\":{\"type\":\"nGram\",\"min_gram\":\"2\",\"max_gram\":\"2\",\"token_chars\":[\"letter\",\"digit\"]},"
                + "\"2gram_synonym\":{\"type\":\"ngram_synonym\",\"n\":\"2\",\"synonyms_path\":\"synonym.txt\"}"
                + "},"//
                + "\"filter\":{"//
                + "\"synonym\":{\"type\":\"synonym\",\"synonyms_path\":\"synonym.txt\"}"
                + "},"//
                + "\"analyzer\":{"
                + "\"2gram_analyzer\":{\"type\":\"custom\",\"tokenizer\":\"2gram\",\"filter\":[\"synonym\"]},"
                + "\"2gram_synonym_analyzer\":{\"type\":\"custom\",\"tokenizer\":\"2gram_synonym\"}"
                + "}"//
                + "}}}";
        runner.createIndex(index,
                ImmutableSettings.builder().loadFromSource(indexSettings)
                        .build());

        // create a mapping
        final XContentBuilder mappingBuilder = XContentFactory.jsonBuilder()//
                .startObject()//
                .startObject(type)//
                .startObject("properties")//

                // id
                .startObject("id")//
                .field("type", "string")//
                .field("index", "not_analyzed")//
                .endObject()//

                // msg1
                .startObject("msg1")//
                .field("type", "string")//
                .field("analyzer", "2gram_synonym_analyzer")//
                .endObject()//

                // msg2
                .startObject("msg2")//
                .field("type", "string")//
                .field("analyzer", "2gram_analyzer")//
                .endObject()//

                .endObject()//
                .endObject()//
                .endObject();
        runner.createMapping(index, type, mappingBuilder);

        for (int i = 1; i <= numOfDocs; i++) {
            final IndexResponse indexResponse1 = runner.insert(index, type,
                    String.valueOf(i),
                    "{\"msg1\":\"あいうえお\", \"msg2\":\"あいうえお\", \"id\":\"" + i
                            + "\"}");
            assertTrue(indexResponse1.isCreated());
        }
        runner.refresh();

        // 1000ドキュメント作成されたか確認
        {
            final SearchResponse searchResponse = runner.search(index, type,
                    null, null, 0, 10);
            assertEquals(numOfDocs, searchResponse.getHits().getTotalHits());
        }

        assertDocCount(0, index, type, "msg1", "あ");
        assertDocCount(numOfDocs, index, type, "msg1", "あい");
        assertDocCount(numOfDocs, index, type, "msg1", "あいう");
        assertDocCount(numOfDocs, index, type, "msg1", "あいうえ");
        assertDocCount(numOfDocs, index, type, "msg1", "あいうえお");

        assertDocCount(0, index, type, "msg1", "かいうえお");
        assertDocCount(0, index, type, "msg1", "かきいうえお");
        assertDocCount(0, index, type, "msg1", "かきいうえ");
        assertDocCount(0, index, type, "msg1", "かきいう");
        assertDocCount(0, index, type, "msg1", "かきい");
        assertDocCount(0, index, type, "msg1", "かき");
        assertDocCount(0, index, type, "msg1", "か");

        assertDocCount(0, index, type, "msg2", "あ");
        assertDocCount(numOfDocs, index, type, "msg2", "あい");
        assertDocCount(numOfDocs, index, type, "msg2", "あいう");
        assertDocCount(numOfDocs, index, type, "msg2", "あいうえ");
        assertDocCount(numOfDocs, index, type, "msg2", "あいうえお");
        assertDocCount(0, index, type, "msg2", "か");
        assertDocCount(0, index, type, "msg2", "かき");
    }

    private void assertDocCount(int expected, final String index,
            final String type, final String field, final String value) {
        final SearchResponse searchResponse = runner.search(index, type,
                QueryBuilders.matchQuery(field, value).type(Type.PHRASE), null,
                0, numOfDocs);
        assertEquals(expected, searchResponse.getHits().getTotalHits());
    }
}
