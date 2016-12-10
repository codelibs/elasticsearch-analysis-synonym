package org.codelibs.elasticsearch.synonym.service;

import org.codelibs.elasticsearch.synonym.SynonymPlugin;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.analysis.AnalysisRegistry;

public class SynonymAnalysisService extends AbstractLifecycleComponent {

    @Inject
    public SynonymAnalysisService(final Settings settings, final AnalysisRegistry analysisRegistry,
            final SynonymPlugin.PluginComponent pluginComponent) {
        super(settings);
        pluginComponent.setAnalysisRegistry(analysisRegistry);
    }

    @Override
    protected void doStart() {
        // nothing
    }

    @Override
    protected void doStop() {
        // nothing
    }

    @Override
    protected void doClose() {
        // nothing
    }

}
