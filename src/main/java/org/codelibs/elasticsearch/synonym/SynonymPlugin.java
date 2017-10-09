package org.codelibs.elasticsearch.synonym;

import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.codelibs.elasticsearch.synonym.analysis.NGramSynonymTokenizerFactory;
import org.codelibs.elasticsearch.synonym.analysis.SynonymTokenFilterFactory;
import org.codelibs.elasticsearch.synonym.service.SynonymAnalysisService;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.component.LifecycleComponent;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AnalysisRegistry;
import org.elasticsearch.index.analysis.TokenFilterFactory;
import org.elasticsearch.index.analysis.TokenizerFactory;
import org.elasticsearch.indices.analysis.AnalysisModule.AnalysisProvider;
import org.elasticsearch.plugins.AnalysisPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.watcher.ResourceWatcherService;

public class SynonymPlugin extends Plugin implements AnalysisPlugin {

    private final PluginComponent pluginComponent = new PluginComponent();

    @Override
    public Collection<Class<? extends LifecycleComponent>> getGuiceServiceClasses() {
        return singletonList(SynonymAnalysisService.class);
    }

    @Override
    public Collection<Object> createComponents(Client client, ClusterService clusterService, ThreadPool threadPool,
            ResourceWatcherService resourceWatcherService, ScriptService scriptService,
            NamedXContentRegistry xContentRegistry, Environment environment,
            NodeEnvironment nodeEnvironment, NamedWriteableRegistry namedWriteableRegistry) {
        final Collection<Object> components = new ArrayList<>();
        components.add(pluginComponent);
        return components;
    }

    @Override
    public Map<String, AnalysisProvider<TokenFilterFactory>> getTokenFilters() {
        final Map<String, AnalysisProvider<TokenFilterFactory>> extra = new HashMap<>();
        extra.put("synonym_filter", new AnalysisProvider<TokenFilterFactory>() {

            @Override
            public TokenFilterFactory get(final IndexSettings indexSettings, final Environment environment, final String name, final Settings settings)
                    throws IOException {
                return new SynonymTokenFilterFactory(indexSettings, environment, name, settings, pluginComponent.getAnalysisRegistry());
            }

            @Override
            public boolean requiresAnalysisSettings() {
                return true;
            }
        });
        return extra;
    }

    @Override
    public Map<String, AnalysisProvider<TokenizerFactory>> getTokenizers() {
        return singletonMap("ngram_synonym", NGramSynonymTokenizerFactory::new);
    }

    public static class PluginComponent {

        private AnalysisRegistry analysisRegistry;

        public AnalysisRegistry getAnalysisRegistry() {
            return analysisRegistry;
        }

        public void setAnalysisRegistry(final AnalysisRegistry analysisRegistry) {
            this.analysisRegistry = analysisRegistry;
        }

    }
}
