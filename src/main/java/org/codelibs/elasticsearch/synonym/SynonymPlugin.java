package org.codelibs.elasticsearch.synonym;

import org.codelibs.elasticsearch.synonym.analysis.NGramSynonymTokenizerFactory;
import org.elasticsearch.index.analysis.AnalysisModule;
import org.elasticsearch.plugins.AbstractPlugin;

public class SynonymPlugin extends AbstractPlugin {
    @Override
    public String name() {
        return "SynonymPlugin";
    }

    @Override
    public String description() {
        return "This plugin provide N-Gram Synonym Tokenizer..";
    }

    public void onModule(AnalysisModule module) {
        module.addTokenizer("ngram_synonym", NGramSynonymTokenizerFactory.class);
    }

}
