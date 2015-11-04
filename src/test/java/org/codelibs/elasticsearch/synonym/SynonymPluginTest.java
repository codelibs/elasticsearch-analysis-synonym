package org.codelibs.elasticsearch.synonym;

import static org.codelibs.elasticsearch.runner.ElasticsearchClusterRunner.newConfigs;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.codelibs.elasticsearch.runner.ElasticsearchClusterRunner;
import org.codelibs.elasticsearch.runner.net.Curl;
import org.codelibs.elasticsearch.runner.net.CurlResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.Settings.Builder;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.query.MatchQueryBuilder.Type;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.node.Node;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SynonymPluginTest {

    private ElasticsearchClusterRunner runner;

    private File[] synonymFiles;

    private int numOfNode = 2;

    private int numOfDocs = 1000;

    @Before
    public void setUp() throws Exception {
        runner = new ElasticsearchClusterRunner();
        runner.onBuild(new ElasticsearchClusterRunner.Builder() {
            @Override
            public void build(final int number, final Builder settingsBuilder) {
                settingsBuilder.put("http.cors.enabled", true);
                settingsBuilder.put("index.number_of_replicas", 0);
                settingsBuilder.put("index.number_of_shards", 3);
                settingsBuilder.put("http.cors.allow-origin", "*");
                settingsBuilder.putArray("discovery.zen.ping.unicast.hosts", "localhost:9301-9399");
                settingsBuilder.put("plugin.types", "org.codelibs.elasticsearch.synonym.SynonymPlugin");
            }
        }).build(newConfigs().numOfNode(numOfNode)
                .clusterName(UUID.randomUUID().toString()));

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
                Settings.builder().loadFromSource(indexSettings)
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
                Settings.builder().loadFromSource(indexSettings)
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
                Settings.builder().loadFromSource(indexSettings)
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

    @Test
    public void test_synonymPath_update() throws Exception {
        synonymFiles = new File[numOfNode];
        for (int i = 0; i < numOfNode; i++) {
            String confPath = runner.getNode(i).settings().get("path.conf");
            synonymFiles[i] = new File(confPath, "synonym.txt");
            updateDictionary(synonymFiles[i], "東京,とうきょう");
        }

        runner.ensureYellow();
        Node node = runner.node();

        final String index = "dataset";
        final String type = "item";

        final String indexSettings = "{\"index\":{\"analysis\":{"
                + "\"tokenizer\":{"//
                + "\"2gram\":{\"type\":\"nGram\",\"min_gram\":\"2\",\"max_gram\":\"2\",\"token_chars\":[\"letter\",\"digit\"]},"
                + "\"2gram_synonym\":{\"type\":\"ngram_synonym\",\"n\":\"2\",\"synonyms_path\":\"synonym.txt\",\"dynamic_reload\":true,\"reload_interval\":\"1s\"}"
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
                Settings.builder().loadFromSource(indexSettings)
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

        final IndexResponse indexResponse1 = runner.insert(index, type, "1",
                "{\"msg1\":\"東京\", \"msg2\":\"東京\", \"id\":\"1\"}");
        assertTrue(indexResponse1.isCreated());
        runner.refresh();

        for (int i = 0; i < 1000; i++) {
            assertDocCount(1, index, type, "msg1", "東京");
            assertDocCount(1, index, type, "msg1", "とうきょう");
            assertDocCount(0, index, type, "msg1", "TOKYO");

            assertDocCount(1, index, type, "msg2", "東京");
            assertDocCount(0, index, type, "msg2", "とうきょう");
            assertDocCount(0, index, type, "msg2", "TOKYO");

            try (CurlResponse response = Curl
                    .post(node, "/" + index + "/_analyze")
                    .param("analyzer", "2gram_synonym_analyzer").body("東京")
                    .execute()) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> tokens = (List<Map<String, Object>>) response
                        .getContentAsMap().get("tokens");
                String token = tokens.get(1).get("token").toString();
                assertEquals("とうきょう", token);
            }
        }

        // changing a file timestamp
        Thread.sleep(2000);

        for (int i = 0; i < numOfNode; i++) {
            updateDictionary(synonymFiles[i], "東京,TOKYO");
        }

        final IndexResponse indexResponse2 = runner.insert(index, type, "2",
                "{\"msg1\":\"東京\", \"msg2\":\"東京\", \"id\":\"2\"}");
        assertTrue(indexResponse2.isCreated());
        runner.refresh();

        for (int i = 0; i < 1000; i++) {
            assertDocCount(2, index, type, "msg1", "東京");
            assertDocCount(0, index, type, "msg1", "とうきょう");
            assertDocCount(2, index, type, "msg1", "TOKYO");

            assertDocCount(2, index, type, "msg2", "東京");
            assertDocCount(0, index, type, "msg2", "とうきょう");
            assertDocCount(0, index, type, "msg2", "TOKYO");

            try (CurlResponse response = Curl
                    .post(node, "/" + index + "/_analyze")
                    .param("analyzer", "2gram_synonym_analyzer").body("東京")
                    .execute()) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> tokens = (List<Map<String, Object>>) response
                        .getContentAsMap().get("tokens");
                String token = tokens.get(1).get("token").toString();
                assertEquals("tokyo", token);
            }
        }
    }


    @Test
    public void test_synonymFilterPath_update() throws Exception {
        synonymFiles = new File[numOfNode];
        for (int i = 0; i < numOfNode; i++) {
            String confPath = runner.getNode(i).settings().get("path.conf");
            synonymFiles[i] = new File(confPath, "synonym.txt");
            updateDictionary(synonymFiles[i], "東京,とうきょう\nああ,嗚呼");
        }

        runner.ensureYellow();
        Node node = runner.node();

        final String index = "dataset";
        final String type = "item";

        final String indexSettings = "{\"index\":{\"analysis\":{"
                + "\"tokenizer\":{"//
                + "\"2gram\":{\"type\":\"nGram\",\"min_gram\":\"2\",\"max_gram\":\"2\",\"token_chars\":[\"letter\",\"digit\"]}"
                + "},"//
                + "\"filter\":{"//
                + "\"synonym\":{       \"type\":\"synonym_filter\",\"synonyms_path\":\"synonym.txt\",\"ignore_case\":true,\"tokenizer\":\"2gram\",\"min_gram\":\"2\",\"max_gram\":\"2\",\"token_chars\":[\"letter\",\"digit\"]},"
                + "\"synonym_reload\":{\"type\":\"synonym_filter\",\"synonyms_path\":\"synonym.txt\",\"ignore_case\":true,\"tokenizer\":\"2gram\",\"min_gram\":\"2\",\"max_gram\":\"2\",\"token_chars\":[\"letter\",\"digit\"],\"dynamic_reload\":true,\"reload_interval\":\"1s\"}"
                + "},"//
                + "\"analyzer\":{"
                + "\"2gram_analyzer\":{\"type\":\"custom\",\"tokenizer\":\"2gram\",\"filter\":[\"lowercase\"]},"
                + "\"2gram_synonym_analyzer\":{\"type\":\"custom\",\"tokenizer\":\"2gram\",\"filter\":[\"synonym\"]},"
                + "\"2gram_reload_analyzer\":{\"type\":\"custom\",\"tokenizer\":\"2gram\",\"filter\":[\"synonym_reload\"]}"
                + "}"//
                + "}}}";
        runner.createIndex(index,
                Settings.builder().loadFromSource(indexSettings)
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
                .field("analyzer", "2gram_reload_analyzer")//
                .endObject()//

                // msg2
                .startObject("msg2")//
                .field("type", "string")//
                .field("analyzer", "2gram_synonym_analyzer")//
                .endObject()//

                .endObject()//
                .endObject()//
                .endObject();
        runner.createMapping(index, type, mappingBuilder);

        final IndexResponse indexResponse1 = runner.insert(index, type, "1",
                "{\"msg1\":\"東京\", \"msg2\":\"東京\", \"id\":\"1\"}");
        assertTrue(indexResponse1.isCreated());
        final IndexResponse indexResponse10 = runner.insert(index, type, "10",
                "{\"msg1\":\"ああ\", \"msg2\":\"ああ\", \"id\":\"10\"}");
        assertTrue(indexResponse10.isCreated());
        runner.refresh();

        for (int i = 0; i < 1000; i++) {
            assertDocCount(1, index, type, "msg1", "東京", "2gram_analyzer");
            assertDocCount(1, index, type, "msg1", "とうきょう", "2gram_analyzer");
            assertDocCount(0, index, type, "msg1", "TOKYO", "2gram_analyzer");

            assertDocCount(1, index, type, "msg2", "東京");
            assertDocCount(1, index, type, "msg2", "とうきょう");
            assertDocCount(0, index, type, "msg2", "TOKYO");

            assertDocCount(1, index, type, "msg1", "ああ");
            assertDocCount(1, index, type, "msg1", "嗚呼");
            assertDocCount(0, index, type, "msg1", "あゝ");

            try (CurlResponse response = Curl
                    .post(node, "/" + index + "/_analyze")
                    .param("analyzer", "2gram_reload_analyzer").body("東京")
                    .execute()) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> tokens = (List<Map<String, Object>>) response
                        .getContentAsMap().get("tokens");
                assertEquals("東京", tokens.get(0).get("token").toString());
                assertEquals("とう", tokens.get(1).get("token").toString());
                assertEquals("うき", tokens.get(2).get("token").toString());
                assertEquals("きょ", tokens.get(3).get("token").toString());
                assertEquals("ょう", tokens.get(4).get("token").toString());
            }
        }

        // changing a file timestamp
        Thread.sleep(2000);

        for (int i = 0; i < numOfNode; i++) {
            updateDictionary(synonymFiles[i], "東京,TOKYO\nああ,あゝ");
        }

        final IndexResponse indexResponse2 = runner.insert(index, type, "2",
                "{\"msg1\":\"東京\", \"msg2\":\"東京\", \"id\":\"2\"}");
        assertTrue(indexResponse2.isCreated());
        final IndexResponse indexResponse11 = runner.insert(index, type, "11",
                "{\"msg1\":\"ああ\", \"msg2\":\"ああ\", \"id\":\"11\"}");
        assertTrue(indexResponse11.isCreated());
        runner.refresh();

        for (int i = 0; i < 1000; i++) {
            assertDocCount(2, index, type, "msg1", "東京", "2gram_analyzer");
            assertDocCount(1, index, type, "msg1", "とうきょう", "2gram_analyzer");
            assertDocCount(1, index, type, "msg1", "TOKYO", "2gram_analyzer");

            assertDocCount(2, index, type, "msg2", "東京");
            assertDocCount(2, index, type, "msg2", "とうきょう");
            assertDocCount(0, index, type, "msg2", "TOKYO");

            assertDocCount(2, index, type, "msg1", "ああ");
            assertDocCount(1, index, type, "msg1", "嗚呼");
            assertDocCount(2, index, type, "msg1", "あゝ");

            try (CurlResponse response = Curl
                    .post(node, "/" + index + "/_analyze")
                    .param("analyzer", "2gram_reload_analyzer").body("東京")
                    .execute()) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> tokens = (List<Map<String, Object>>) response
                        .getContentAsMap().get("tokens");
                assertEquals("東京", tokens.get(0).get("token").toString());
                assertEquals("to", tokens.get(1).get("token").toString());
                assertEquals("ok", tokens.get(2).get("token").toString());
                assertEquals("ky", tokens.get(3).get("token").toString());
                assertEquals("yo", tokens.get(4).get("token").toString());
            }
        }
    }

    private void assertDocCount(int expected, final String index,
            final String type, final String field, final String value) {
        assertDocCount(expected, index, type, field, value, null);
    }

    private void assertDocCount(int expected, final String index,
            final String type, final String field, final String value,
            String analyzer) {
        final SearchResponse searchResponse = runner.search(index, type,
                QueryBuilders.matchQuery(field, value).type(Type.PHRASE)
                        .analyzer(analyzer), null, 0, numOfDocs);
        assertEquals(expected, searchResponse.getHits().getTotalHits());
    }
}
