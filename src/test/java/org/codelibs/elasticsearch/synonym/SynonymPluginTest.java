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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SynonymPluginTest {

    private ElasticsearchClusterRunner runner;

    private File[] synonymFiles;

    private int numOfNode = 1;

    @Before
    public void setUp() throws Exception {
        runner = new ElasticsearchClusterRunner();
        runner.onBuild(new ElasticsearchClusterRunner.Builder() {
            @Override
            public void build(final int number, final Builder settingsBuilder) {
                settingsBuilder.put("http.cors.enabled", true);
                settingsBuilder.put("index.number_of_replicas", 0);
            }
        }).build(newConfigs().ramIndexStore().numOfNode(numOfNode));

        synonymFiles = new File[numOfNode];
        for (int i = 0; i < numOfNode; i++) {
            String confPath = runner.getNode(i).settings().get("path.conf");
            synonymFiles[i] = new File(confPath, "synonym.txt");
            updateDictionary(synonymFiles[i],
                    "あい,かき,さしす,たちつて,なにぬねの¥nうえお,くけこ,すせ");
        }

        runner.ensureYellow();
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
    }

    @Test
    public void test_runCluster() throws Exception {
        final String index = "dataset";
        final String type = "item";

        final String indexSettings = "{\"index\":{\"analysis\":{"
                + "\"tokenizer\":{"//
                + "\"ngramSynonym\":{\"type\":\"ngram_synonym\",\"n\":\"2\",\"synonyms_path\":\"synonym.txt\"}"
                + "},"//
                + "\"analyzer\":{"
                + "\"my_analyzer\":{\"type\":\"custom\",\"tokenizer\":\"ngramSynonym\"}"
                + "}"//
                + "}}}";
        runner.createIndex(index,
                ImmutableSettings.builder().loadFromSource(indexSettings)
                        .build());

        int numOfDocs = 10;
        for (int i = 1; i <= numOfDocs; i++) {
            String msg = null;
            switch (i % 5) {
            case 0:
                msg = "あいうえお";
                break;
            case 1:
                msg = "かきくけこ";
                break;
            case 2:
                msg = "さしすせそ";
                break;
            case 3:
                msg = "たちつてと";
                break;
            case 4:
                msg = "なにぬねの";
                break;
            }
            final IndexResponse indexResponse1 = runner.insert(index, type,
                    String.valueOf(i), "{\"msg\":\"" + msg + "\", \"id\":\""
                            + i + "\"}");
            assertTrue(indexResponse1.isCreated());
        }
        runner.refresh();

        // 1000ドキュメント作成されたか確認
        {
            final SearchResponse searchResponse = runner.search(index, type,
                    null, null, 0, 10);
            assertEquals(numOfDocs, searchResponse.getHits().getTotalHits());
        }

    }
}
