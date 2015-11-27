package org.codelibs.elasticsearch.synonym.analysis;

import org.apache.lucene.analysis.Tokenizer;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.assistedinject.Assisted;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.analysis.AbstractTokenizerFactory;
import org.elasticsearch.index.settings.IndexSettingsService;

/**
 * Factory for {@link NGramSynonymTokenizer}.
 */
public final class NGramSynonymTokenizerFactory extends
        AbstractTokenizerFactory {

    private final boolean ignoreCase;

    private final int n;

    private final String delimiters;

    private final boolean expand;

    private SynonymLoader synonymLoader = null;

    @Inject
    public NGramSynonymTokenizerFactory(Index index, IndexSettingsService indexSettingsService, Environment env, @Assisted String name,
            @Assisted Settings settings) {
        super(index, indexSettingsService.getSettings(), name, settings);
        ignoreCase = settings.getAsBoolean("ignore_case", true);
        n = settings.getAsInt("n", NGramSynonymTokenizer.DEFAULT_N_SIZE);
        delimiters = settings.get("delimiters",
                NGramSynonymTokenizer.DEFAULT_DELIMITERS);
        expand = settings.getAsBoolean("expand", true);

        synonymLoader = new SynonymLoader(env, settings, expand, SynonymLoader.getAnalyzer(ignoreCase));
        if (synonymLoader.getSynonymMap() == null) {
            if (settings.getAsArray("synonyms", null) != null) {
                logger.warn("synonyms values are empty.");
            } else if (settings.get("synonyms_path") != null) {
                logger.warn("synonyms_path[{}] is empty.",
                        settings.get("synonyms_path"));
            } else {
                logger.debug("No synonym data.");
            }
        }
    }

    @Override
    public Tokenizer create() {
        return new NGramSynonymTokenizer(n, delimiters, expand,
                ignoreCase, synonymLoader);
    }
}
