package org.codelibs.elasticsearch.synonym.analysis;

import java.io.IOException;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AbstractTokenFilterFactory;
import org.elasticsearch.index.analysis.AnalysisRegistry;
import org.elasticsearch.index.analysis.TokenizerFactory;
import org.elasticsearch.indices.analysis.AnalysisModule;

public class SynonymTokenFilterFactory extends AbstractTokenFilterFactory {

    private final boolean ignoreCase;

    private SynonymLoader synonymLoader = null;

    public SynonymTokenFilterFactory(final IndexSettings indexSettings, final Environment environment, final String name, final Settings settings,
            final AnalysisRegistry analysisRegistry) throws IOException {
        super(indexSettings, name, settings);

        this.ignoreCase = settings.getAsBoolean("ignore_case", false);
        final boolean expand = settings.getAsBoolean("expand", true);

        final String tokenizerName = settings.get("tokenizer", "whitespace");

        AnalysisModule.AnalysisProvider<TokenizerFactory> tokenizerFactoryFactory = null;
        if (analysisRegistry != null) {
            tokenizerFactoryFactory = analysisRegistry.getTokenizerProvider(tokenizerName, indexSettings);
            if (tokenizerFactoryFactory == null) {
                throw new IllegalArgumentException("failed to find tokenizer [" + tokenizerName + "] for synonym token filter");
            }
        }

        final TokenizerFactory tokenizerFactory = tokenizerFactoryFactory == null ? null
                : tokenizerFactoryFactory.get(indexSettings, environment, tokenizerName, AnalysisRegistry
                        .getSettingsFromIndexSettings(indexSettings, AnalysisRegistry.INDEX_ANALYSIS_TOKENIZER + "." + tokenizerName));

        final Analyzer analyzer = new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(final String fieldName) {
                final Tokenizer tokenizer = tokenizerFactory == null ? new WhitespaceTokenizer() : tokenizerFactory.create();
                final TokenStream stream = ignoreCase ? new LowerCaseFilter(tokenizer) : tokenizer;
                return new TokenStreamComponents(tokenizer, stream);
            }
        };

        synonymLoader = new SynonymLoader(environment, settings, expand, analyzer);
        if (synonymLoader.getSynonymMap() == null) {
            if (settings.getAsList("synonyms", null) != null) {
                logger.warn("synonyms values are empty.");
            } else if (settings.get("synonyms_path") != null) {
                logger.warn("synonyms_path[{}] is empty.", settings.get("synonyms_path"));
            } else {
                throw new IllegalArgumentException("synonym requires either `synonyms` or `synonyms_path` to be configured");
            }
        }
    }

    @Override
    public TokenStream create(final TokenStream tokenStream) {
        // fst is null means no synonyms
        return synonymLoader == null ? tokenStream : new SynonymFilter(tokenStream, synonymLoader, ignoreCase);
    }

}
