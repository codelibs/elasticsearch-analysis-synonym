package org.codelibs.elasticsearch.synonym.analysis;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
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
import org.elasticsearch.common.base.Charsets;
import org.elasticsearch.common.io.FastStringReader;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.analysis.Analysis;

public class SynonymLoader {
	private File reloadableFile = null;

	private Analyzer analyzer;

	private Settings settings;

	private boolean expand;

	private long reloadInterval = 0;

	private Environment env;

	private volatile long lastModified;

	private volatile long lastChecked;

	private volatile SynonymMap synonymMap;

	public SynonymLoader(Environment env, Settings settings, boolean expand,
			boolean ignoreCase) {
		this.env = env;
		this.settings = settings;
		this.expand = expand;
		this.analyzer = getAnalyzer(ignoreCase);

		createSynonymMap(false);
	}

	public boolean isUpdate(long time) {
		if (System.currentTimeMillis() - lastChecked > reloadInterval) {
			lastChecked = System.currentTimeMillis();
			long timestamp = reloadableFile.lastModified();
			if (timestamp != time) {
				synchronized (reloadableFile) {
					if (timestamp != lastModified) {
						createSynonymMap(true);
						return true;
					}
				}
			}
		}

		if (lastModified != time) {
			return true;
		}

		return false;
	}

	public SynonymMap getSynonymMap() {
		return synonymMap;
	}

	protected void createSynonymMap(boolean reload) {
		try (Reader rulesReader = getReader(reload)) {
			if (rulesReader instanceof FastStringReader
					&& ((FastStringReader) rulesReader).length() == 0) {
				synonymMap = null;
				return;
			}

			SynonymMap.Builder parser = null;

			if ("wordnet".equalsIgnoreCase(settings.get("format"))) {
				parser = new WordnetSynonymParser(true, expand, analyzer);
				((WordnetSynonymParser) parser).parse(rulesReader);
			} else {
				parser = new SolrSynonymParser(true, expand, analyzer);
				((SolrSynonymParser) parser).parse(rulesReader);
			}

			SynonymMap localSynonymMap = parser.build();
			if (localSynonymMap.fst == null) {
				synonymMap = null;
				return;
			}

			synonymMap = localSynonymMap;

			if (reloadableFile != null) {
				lastModified = reloadableFile.lastModified();
			} else {
				lastModified = System.currentTimeMillis();
			}

		} catch (Exception e) {
			throw new ElasticsearchIllegalArgumentException(
					"failed to build synonyms", e);
		}
	}

	private Reader getReader(boolean reload) throws IOException {
		if (reload) {
			if (reloadableFile == null) {
				throw new ElasticsearchIllegalArgumentException(
						"reloadableFile is null.");
			}
			return new InputStreamReader(new FileInputStream(reloadableFile),
					Charsets.UTF_8);
		}

		Reader reader = null;
		if (settings.getAsArray("synonyms", null) != null) {
			List<String> rules = Analysis
					.getWordList(env, settings, "synonyms");
			StringBuilder sb = new StringBuilder();
			for (String line : rules) {
				sb.append(line).append(System.getProperty("line.separator"));
			}
			reader = new FastStringReader(sb.toString());
		} else if (settings.get("synonyms_path") != null) {
			if (settings.getAsBoolean("dynamic_reload", false)) {
				String filePath = settings.get("synonyms_path", null);

				if (filePath == null) {
					throw new ElasticsearchIllegalArgumentException(
							"synonyms_path is not found.");
				}

				URL fileUrl = env.resolveConfig(filePath);

				try {
					File file = new File(fileUrl.toURI());
					if (file.exists()) {
						reloadableFile = file;
					}
					reader = new InputStreamReader(fileUrl.openStream(),
							Charsets.UTF_8);
				} catch (Exception e) {
					throw new ElasticsearchIllegalArgumentException(
							"Failed to read " + filePath);
				}

				reloadInterval = settings.getAsTime("reload_interval",
						TimeValue.timeValueMinutes(1)).getMillis();

			} else {
				reader = Analysis.getReaderFromFile(env, settings,
						"synonyms_path");
			}
		} else {
			reader = new FastStringReader("");
		}

		return reader;
	}

	public boolean isReloadable() {
		return reloadableFile != null;
	}

	protected static Analyzer getAnalyzer(final boolean ignoreCase) {
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

	public long getLastModified() {
		return lastModified;
	}
}
