Elasticsearch Analysis Synonym
=======================

## Overview

Elasticsearch Analysis Synonym Plugin provides NGramSynonymTokenizer.
For more details, see [LUCENE-5252](https://issues.apache.org/jira/browse/LUCENE-5252 "LUCENE-5252").

## Version

| Version   | Tested On ES  |
|:---------:|:-------------:|
| master    | 5.3.X         |
| 5.3.0     | 5.3.0         |
| 5.0.0     | 5.0.2         |
| 2.4.0     | 2.4.0         |
| 2.3.0     | 2.3.1         |
| 2.2.0     | 2.2.2         |
| 2.1.1     | 2.1.1         |
| 2.0.0     | 2.0.0         |
| 1.5.0     | 1.5.1         |
| 1.4.3     | 1.4.4         |

### Issues/Questions

Please file an [issue](https://github.com/codelibs/elasticsearch-analysis-synonym/issues "issue").
(Japanese forum is [here](https://github.com/codelibs/codelibs-ja-forum "here").)

## Installation

### For 5.x

    $ $ES_HOME/bin/elasticsearch-plugin install org.codelibs:elasticsearch-analysis-synonym:5.3.0

### For 2.x

    $ $ES_HOME/bin/plugin install org.codelibs/elasticsearch-analysis-synonym/2.4.0

## Getting Started

### Create synonym.txt File

First of all, you need to create a synonym dictionary file, synonym.txt in $ES\_CONF(ex. /etc/elasticsearch).
(The following content is just a sample...)

    $ cat /etc/elasticsearch/synonym.txt
    あ,かき,さしす,たちつて,なにぬねの

### Create Index

NGramSynonymTokenizer is defined as "ngram\_synonym" type.
Creating an index with "ngram\_synonym" is below:

    $ curl -XPUT localhost:9200/sample?pretty -d '
    {
      "settings":{
        "index":{
          "analysis":{
            "tokenizer":{
              "2gram_synonym":{
                "type":"ngram_synonym",
                "n":"2",
                "synonyms_path":"synonym.txt"
              }
            },
            "analyzer":{
              "2gram_synonym_analyzer":{
                "type":"custom",
                "tokenizer":"2gram_synonym"
              }
            }
          }
        }
      },
      "mappings":{
        "item":{
          "properties":{
            "id":{
              "type":"string",
              "index":"not_analyzed"
            },
            "msg":{
              "type":"string",
              "analyzer":"2gram_synonym_analyzer"
            }
          }
        }
      }
    }'

and then insert data:

    $ curl -XPOST localhost:9200/sample/item/1 -d '
    {
      "id":"1",
      "msg":"あいうえお"
    }'

### Check Search Results

Try searching...

    $ curl -XPOST "http://localhost:9200/sample/_search" -d '
    {
       "query": {
          "match_phrase": {
             "msg": "あ"
          }
       }
    }'

    $ curl -XPOST "http://localhost:9200/sample/_search" -d '
    {
       "query": {
          "match_phrase": {
             "msg": "あい"
          }
       }
    }'

    $ curl -XPOST "http://localhost:9200/sample/_search" -d '
    {
       "query": {
          "match_phrase": {
             "msg": "かき"
          }
       }
    }'

    $ curl -XPOST "http://localhost:9200/sample/_search" -d '
    {
       "query": {
          "match_phrase": {
             "msg": "かきい"
          }
       }
    }'

### Reload synonyms_path File Dynamically

To add "dynamic\_reload" property as true, NGramSynonymTokenizer reloads synonyms\_path file on the fly(actually, it's reload on reset() method call).
If you want to change an interval time to check a file timestamp, add "reload\_interval".

    $ curl -XPUT localhost:9200/sample?pretty -d '
    {
      "settings":{
        "index":{
          "analysis":{
            "tokenizer":{
              "2gram_synonym":{
                "type":"ngram_synonym",
                "n":"2",
                "synonyms_path":"synonym.txt",
                "dynamic_reload":true,
                "reload_interval":"10s"
              }
            },
    ...

