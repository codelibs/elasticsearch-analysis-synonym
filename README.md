Elasticsearch Analysis Synonym
=======================

## Overview

Elasticsearch Analysis Synonym Plugin provides NGramSynonymTokenizer.
For more details, see [LUCENE-5252](https://issues.apache.org/jira/browse/LUCENE-5252 "LUCENE-5252").

## Version

| Version   | elasticsearch |
|:---------:|:-------------:|
| master    | 1.4.X         |
| 1.4.2     | 1.4.2         |

### Issues/Questions

Please file an [issue](https://github.com/codelibs/elasticsearch-analysis-synonym/issues "issue").
(Japanese forum is [here](https://github.com/codelibs/codelibs-ja-forum "here").)

## Installation

    $ $ES_HOME/bin/plugin --install org.codelibs/elasticsearch-analysis-synonym/1.4.2

## Getting Started

### Create synonym.txt File

First of all, you need to create a synonym dictionary file, synonym.txt in $ES_CONF(ex. /etc/elasticsearch).
(The following content is just a sample...)

    $ cat /etc/elasticsearch/synonym.txt
    あ,かき,さしす,たちつて,なにぬねの

### Create Index

NGramSynonymTokenizer is defined as "ngram_synonym" type.
Creating an index with "ngram_synonym" is below:

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


