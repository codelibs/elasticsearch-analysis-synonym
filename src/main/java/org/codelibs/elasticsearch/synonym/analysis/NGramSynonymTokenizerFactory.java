package org.codelibs.elasticsearch.synonym.analysis;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.Reader;
import java.util.List;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.KeywordTokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.synonym.SolrSynonymParser;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.analysis.synonym.WordnetSynonymParser;
import org.elasticsearch.ElasticsearchIllegalArgumentException;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.assistedinject.Assisted;
import org.elasticsearch.common.io.FastStringReader;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.analysis.AbstractTokenizerFactory;
import org.elasticsearch.index.analysis.Analysis;
import org.elasticsearch.index.settings.IndexSettings;

/**
 * Factory for {@link NGramSynonymTokenizer}.
 */
public final class NGramSynonymTokenizerFactory extends
        AbstractTokenizerFactory {

    private final boolean ignoreCase;

    private final int n;

    private final String delimiters;

    private final boolean expand;

    private SynonymMap synonymMap;

    @Inject
    public NGramSynonymTokenizerFactory(Index index,
            @IndexSettings Settings indexSettings, Environment env,
            @Assisted String name, @Assisted Settings settings) {
        super(index, indexSettings, name, settings);
        ignoreCase = settings.getAsBoolean("ignore_case", true);
        n = settings.getAsInt("n", NGramSynonymTokenizer.DEFAULT_N_SIZE);
        delimiters = settings.get("delimiters",
                NGramSynonymTokenizer.DEFAULT_DELIMITERS);
        expand = settings.getAsBoolean("expand", true);

        Analyzer analyzer = getAnalyzer(ignoreCase);

        try (Reader rulesReader = getSynonymReader(env, settings)) {
            SynonymMap.Builder parser = null;

            if ("wordnet".equalsIgnoreCase(settings.get("format"))) {
                parser = new WordnetSynonymParser(true, expand, analyzer);
                ((WordnetSynonymParser) parser).parse(rulesReader);
            } else {
                parser = new SolrSynonymParser(true, expand, analyzer);
                ((SolrSynonymParser) parser).parse(rulesReader);
            }

            synonymMap = parser.build();
        } catch (Exception e) {
            throw new ElasticsearchIllegalArgumentException(
                    "failed to build synonyms", e);
        }
    }

    private Reader getSynonymReader(Environment env, Settings settings) {
        if (settings.getAsArray("synonyms", null) != null) {
            List<String> rules = Analysis
                    .getWordList(env, settings, "synonyms");
            StringBuilder sb = new StringBuilder();
            for (String line : rules) {
                sb.append(line).append(System.getProperty("line.separator"));
            }
            return new FastStringReader(sb.toString());
        } else if (settings.get("synonyms_path") != null) {
            return Analysis.getReaderFromFile(env, settings, "synonyms_path");
        } else {
            return new FastStringReader("");
        }
    }

    public static Analyzer getAnalyzer(final boolean ignoreCase) {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName,
                    Reader reader) {
                Tokenizer tokenizer = new KeywordTokenizer(reader);
                @SuppressWarnings("resource")
                TokenStream stream = ignoreCase ? new LowerCaseFilter(tokenizer)
                        : tokenizer;
                return new TokenStreamComponents(tokenizer, stream);
            }
        };
    }

    public Tokenizer create(Reader input) {
        return new NGramSynonymTokenizer(input, n, delimiters, expand,
                ignoreCase, synonymMap);
    }

}
