package org.codelibs.elasticsearch.synonym;

import org.codelibs.elasticsearch.synonym.analysis.NGramSynonymTokenizerFactory;
import org.codelibs.elasticsearch.synonym.analysis.SynonymTokenFilterFactory;
import org.elasticsearch.index.analysis.AnalysisModule;
import org.elasticsearch.plugins.Plugin;

public class SynonymPlugin extends Plugin {
    @Override
    public String name() {
        return "analysis-synonym";
    }

    @Override
    public String description() {
        return "This plugin provides N-Gram Synonym Tokenizer.";
    }

    public void onModule(AnalysisModule module) {
        module.addTokenizer("ngram_synonym", NGramSynonymTokenizerFactory.class);
        module.addTokenFilter("synonym_filter", SynonymTokenFilterFactory.class);
    }

}
