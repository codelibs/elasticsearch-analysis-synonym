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

import static org.junit.Assert.*;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.text.ParseException;
import java.util.PriorityQueue;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.synonym.SolrSynonymParser;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.codelibs.elasticsearch.synonym.analysis.NGramSynonymTokenizer.MyToken;
import org.junit.Test;

public class NGramSynonymTokenizerTest {

  @Test
  public void testGetNextBlock() throws Exception {
    NGramSynonymTokenizer tokenizer = getTokenizer("あいうえお");
    assertBlocks(tokenizer, "0", "あいうえお");

    tokenizer = getTokenizer("あいうえお ");
    assertBlocks(tokenizer, "0", "あいうえお");

    tokenizer = getTokenizer("あいうえお かきくけこ");
    assertBlocks(tokenizer, "0,6", "あいうえお", "かきくけこ");

    tokenizer = getTokenizer("あいうえお \tかきくけこ");
    assertBlocks(tokenizer, "0,7", "あいうえお", "かきくけこ");

    tokenizer = getTokenizer("あいうえお \t　かきくけこ さしすせそ");
    assertBlocks(tokenizer, "0,8,14", "あいうえお", "かきくけこ", "さしすせそ");
  }

  @Test
  public void testGetNextBlockLong() throws Exception {
    String src1 = getLengthDummyBlock(NGramSynonymTokenizer.BUFFER_SIZE - 2, 'a', ' ');
    NGramSynonymTokenizer tokenizer = getTokenizer(src1);
    assertBlocks(tokenizer, "0", src1.substring(0, NGramSynonymTokenizer.BUFFER_SIZE - 2));

    src1 = getLengthDummyBlock(NGramSynonymTokenizer.BUFFER_SIZE - 1, 'a', ' ');
    tokenizer = getTokenizer(src1);
    assertBlocks(tokenizer, "0", src1.substring(0, NGramSynonymTokenizer.BUFFER_SIZE - 1));

    src1 = getLengthDummyBlock(NGramSynonymTokenizer.BUFFER_SIZE, 'a', ' ');
    tokenizer = getTokenizer(src1);
    assertBlocks(tokenizer, "0", src1.substring(0, NGramSynonymTokenizer.BUFFER_SIZE));

    src1 = getLengthDummyBlock(NGramSynonymTokenizer.BUFFER_SIZE - 2, 'a', ' ');
    String src2 = getLengthDummyBlock(NGramSynonymTokenizer.BUFFER_SIZE - 2, 'a', ' ');
    tokenizer = getTokenizer(src1 + src2);
    assertBlocks(tokenizer, new int[]{0, NGramSynonymTokenizer.BUFFER_SIZE - 1},
        src1.substring(0, NGramSynonymTokenizer.BUFFER_SIZE - 2),
        src2.substring(0, NGramSynonymTokenizer.BUFFER_SIZE - 2));

    src1 = getLengthDummyBlock(NGramSynonymTokenizer.BUFFER_SIZE - 1, 'a', ' ');
    src2 = getLengthDummyBlock(NGramSynonymTokenizer.BUFFER_SIZE - 2, 'a', ' ');
    tokenizer = getTokenizer(src1 + src2);
    assertBlocks(tokenizer, new int[]{0, NGramSynonymTokenizer.BUFFER_SIZE},
        src1.substring(0, NGramSynonymTokenizer.BUFFER_SIZE - 1),
        src2.substring(0, NGramSynonymTokenizer.BUFFER_SIZE - 2));

    src1 = getLengthDummyBlock(NGramSynonymTokenizer.BUFFER_SIZE, 'a', ' ');
    src2 = getLengthDummyBlock(NGramSynonymTokenizer.BUFFER_SIZE - 2, 'a', ' ');
    tokenizer = getTokenizer(src1 + src2);
    assertBlocks(tokenizer, new int[]{0, NGramSynonymTokenizer.BUFFER_SIZE + 1},
        src1.substring(0, NGramSynonymTokenizer.BUFFER_SIZE),
        src2.substring(0, NGramSynonymTokenizer.BUFFER_SIZE - 2));

    src1 = getLengthDummyBlock(NGramSynonymTokenizer.BUFFER_SIZE + 1, 'a', ' ');
    src2 = getLengthDummyBlock(NGramSynonymTokenizer.BUFFER_SIZE - 2, 'a', ' ');
    tokenizer = getTokenizer(src1 + src2);
    assertBlocks(tokenizer, new int[]{0, NGramSynonymTokenizer.BUFFER_SIZE + 2},
        src1.substring(0, NGramSynonymTokenizer.BUFFER_SIZE + 1),
        src2.substring(0, NGramSynonymTokenizer.BUFFER_SIZE - 2));

    src1 = getLengthDummyBlock(NGramSynonymTokenizer.BUFFER_SIZE + 2, 'a', '\n', '\r');
    src2 = getLengthDummyBlock(NGramSynonymTokenizer.BUFFER_SIZE - 2, 'a', ' ');
    tokenizer = getTokenizer(src1 + src2);
    assertBlocks(tokenizer, new int[]{0, NGramSynonymTokenizer.BUFFER_SIZE + 4},
        src1.substring(0, NGramSynonymTokenizer.BUFFER_SIZE + 2),
        src2.substring(0, NGramSynonymTokenizer.BUFFER_SIZE - 2));
  }
  
  private NGramSynonymTokenizer getTokenizer(String input) throws IOException {
    NGramSynonymTokenizer tokenizer = new NGramSynonymTokenizer(new StringReader(input),
        NGramSynonymTokenizer.DEFAULT_N_SIZE,
        NGramSynonymTokenizer.DEFAULT_DELIMITERS, false, true, null);
    tokenizer.reset();
    return tokenizer;
  }
  
  private void assertBlocks(NGramSynonymTokenizer tokenizer, String expBlkStarts, String... expBlocks) throws Exception {
    String[] params = expBlkStarts.split(",");
    final int len = params.length;
    int[] exps = new int[len];
    for(int i = 0; i < len; i++){
      exps[i] = Integer.parseInt(params[i]);
    }
    assertBlocks(tokenizer, exps, expBlocks);
  }
  
  private void assertBlocks(NGramSynonymTokenizer tokenizer, int[] expBlkStarts, String... expBlocks) throws Exception {
    final int len = expBlkStarts.length;
    assertEquals(len, expBlocks.length);
    
    for(int i = 0; i < len; i++){
      assertTrue(tokenizer.getNextBlock());
      assertEquals(expBlkStarts[i], tokenizer.blkStart);
      assertEquals(expBlocks[i], tokenizer.block.toString());
    }
    
    assertFalse(tokenizer.getNextBlock());
  }
  
  private String getLengthDummyBlock(int length, char blockChar, char... eobChars){
    StringBuilder sb = new StringBuilder();
    for(int i = 0; i < length; i++){
      sb.append(blockChar);
    }
    for(char eobChar : eobChars){
      sb.append(eobChar);
    }
    return sb.toString();
  }
  
  @Test
  public void testMyTokensComparator() throws Exception {
    PriorityQueue<MyToken> pq = new PriorityQueue<MyToken>(10, new NGramSynonymTokenizer.MyTokensComparator());
    
    MyToken t1 = new MyToken("", 10, 11, 1);
    pq.add(t1);
    MyToken t2 = new MyToken("",  9, 11, 0);
    pq.add(t2);
    MyToken t3 = new MyToken("",  9, 11, 1);
    pq.add(t3);
    MyToken t4 = new MyToken("",  8, 11, 1);
    pq.add(t4);
    MyToken t5 = new MyToken("",  7, 11, 1);
    pq.add(t5);
    MyToken t6 = new MyToken("",  7, 10, 1);
    pq.add(t6);
    
    assertEquals(t6, pq.poll());
    assertEquals(t5, pq.poll());
    assertEquals(t4, pq.poll());
    assertEquals(t3, pq.poll());
    assertEquals(t2, pq.poll());
    assertEquals(t1, pq.poll());
    assertNull(pq.poll());
  }
  
  @Test
  public void testMyTokenIdentical() throws Exception {
    MyToken t1 = new MyToken("token", 10, 11, 1);
    MyToken t2 = new MyToken("token", 10, 11, 1);
    assertFalse(t1.identical(t2));
    assertFalse(t2.identical(t2));
    assertFalse(t2.identical(t1));

    MyToken t3 = new MyToken("token", 10, 11, 0);
    assertTrue(t1.identical(t3));
    assertFalse(t3.identical(t1));

    MyToken t4 = new MyToken("token", 10, 11, 0);
    assertTrue(t1.identical(t4));
    assertTrue(t3.identical(t4));
    assertTrue(t4.identical(t3));
  }
  
  @Test
  public void testGetNextUniqueToken() throws Exception {
    PriorityQueue<MyToken> pq = new PriorityQueue<MyToken>(10, new NGramSynonymTokenizer.MyTokensComparator());
    
    MyToken t1 = new MyToken("t1", 10, 11, 1);
    pq.add(t1);
    MyToken t2 = new MyToken("t2",  9, 11, 0);
    pq.add(t2);
    MyToken t3 = new MyToken("t3",  9, 11, 1);
    pq.add(t3);
    MyToken t4 = new MyToken("t2",  9, 11, 0);
    pq.add(t4);
    MyToken t5 = new MyToken("t5",  8, 11, 1);
    pq.add(t5);
    MyToken t6 = new MyToken("t5",  8, 11, 0);
    pq.add(t6);
    MyToken t7 = new MyToken("t7",  7, 11, 1);
    pq.add(t7);
    MyToken t8 = new MyToken("t8",  7, 10, 1);
    pq.add(t8);
    
    assertEquals(t8, NGramSynonymTokenizer.getNextUniqueToken(pq, null));
    assertEquals(t7, NGramSynonymTokenizer.getNextUniqueToken(pq, t8));
    assertEquals(t5, NGramSynonymTokenizer.getNextUniqueToken(pq, t7));
    assertEquals(t3, NGramSynonymTokenizer.getNextUniqueToken(pq, t5));
    assertEquals(t2, NGramSynonymTokenizer.getNextUniqueToken(pq, t3));
    assertEquals(t1, NGramSynonymTokenizer.getNextUniqueToken(pq, t2));
    assertNull(NGramSynonymTokenizer.getNextUniqueToken(pq, t1));
  }

  @Test
  public void testNullSynonyms() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(1);
    TokenStream stream = a.tokenStream("f", new StringReader("ロンウイット"));
    stream.reset();
    assertTokenStream(stream, "ロ,0,1,1/ン,1,2,1/ウ,2,3,1/イ,3,4,1/ッ,4,5,1/ト,5,6,1");
    
    a = new NGramSynonymTokenizerTestAnalyzer(2);
    stream = a.tokenStream("f", new StringReader("ロンウイット"));
    stream.reset();
    assertTokenStream(stream, "ロン,0,2,1/ンウ,1,3,1/ウイ,2,4,1/イッ,3,5,1/ット,4,6,1");
    stream.close();
    stream = a.tokenStream("f", new StringReader("ロ"));
    stream.reset();
    assertTokenStream(stream, "ロ,0,1,1");
    stream.close();
    stream = a.tokenStream("f", new StringReader("ロン"));
    stream.reset();
    assertTokenStream(stream, "ロン,0,2,1");
    
    a = new NGramSynonymTokenizerTestAnalyzer(3);
    stream = a.tokenStream("f", new StringReader("ロンウイット"));
    stream.reset();
    assertTokenStream(stream, "ロンウ,0,3,1/ンウイ,1,4,1/ウイッ,2,5,1/イット,3,6,1");
    
    a = new NGramSynonymTokenizerTestAnalyzer(4);
    stream = a.tokenStream("f", new StringReader("ロンウイット"));
    stream.reset();
    assertTokenStream(stream, "ロンウイ,0,4,1/ンウイッ,1,5,1/ウイット,2,6,1");
    
    a = new NGramSynonymTokenizerTestAnalyzer(5);
    stream = a.tokenStream("f", new StringReader("ロンウイット"));
    stream.reset();
    assertTokenStream(stream, "ロンウイッ,0,5,1/ンウイット,1,6,1");
    
    a = new NGramSynonymTokenizerTestAnalyzer(6);
    stream = a.tokenStream("f", new StringReader("ロンウイット"));
    stream.reset();
    assertTokenStream(stream, "ロンウイット,0,6,1");
    
    a = new NGramSynonymTokenizerTestAnalyzer(7);
    stream = a.tokenStream("f", new StringReader("ロンウイット"));
    stream.reset();
    assertTokenStream(stream, "ロンウイット,0,6,1");
    
    a = new NGramSynonymTokenizerTestAnalyzer(8);
    stream = a.tokenStream("f", new StringReader("ロンウイット"));
    stream.reset();
    assertTokenStream(stream, "ロンウイット,0,6,1");
  }

  @Test
  public void testSingleSynonym() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(1, false, "a,aa,aaa");
    TokenStream stream = a.tokenStream("f", new StringReader("a"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1");

    a = new NGramSynonymTokenizerTestAnalyzer(1, false, "a,aa,aaa");
    stream = a.tokenStream("f", new StringReader("aa"));
    stream.reset();
    assertTokenStream(stream, "aa,0,2,1");

    a = new NGramSynonymTokenizerTestAnalyzer(1, false, "a,aa,aaa");
    stream = a.tokenStream("f", new StringReader("aaa"));
    stream.reset();
    assertTokenStream(stream, "aaa,0,3,1");

    a = new NGramSynonymTokenizerTestAnalyzer(1, false, "a");
    stream = a.tokenStream("f", new StringReader("a"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1");
  }

  @Test
  public void testSingleSynonymIgnoreCase() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(2, false, "A,AA,AAA");
    TokenStream stream = a.tokenStream("f", new StringReader("aaa"));
    stream.reset();
    assertTokenStream(stream, "aaa,0,3,1");
  }

  @Test
  public void testSingleSynonymExpand() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(1, true, "a,aa,aaa");
    TokenStream stream = a.tokenStream("f", new StringReader("a"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/aaa,0,1,0");

    a = new NGramSynonymTokenizerTestAnalyzer(1, true, "a,aa,aaa");
    stream = a.tokenStream("f", new StringReader("aa"));
    stream.reset();
    assertTokenStream(stream, "aa,0,2,1/a,0,2,0/aaa,0,2,0");

    a = new NGramSynonymTokenizerTestAnalyzer(1, true, "a,aa,aaa");
    stream = a.tokenStream("f", new StringReader("aaa"));
    stream.reset();
    assertTokenStream(stream, "aaa,0,3,1/a,0,3,0/aa,0,3,0");

    a = new NGramSynonymTokenizerTestAnalyzer(1, true, "a");
    stream = a.tokenStream("f", new StringReader("a"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1");
  }

  @Test
  public void testMultipleSynonyms() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(1, false, "a,aa/b,bb");
    TokenStream stream = a.tokenStream("f", new StringReader("ababb"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/b,1,2,1/a,2,3,1/bb,3,5,1");

    a = new NGramSynonymTokenizerTestAnalyzer(1, false, "a,aa/b,bb/c,cc");
    stream = a.tokenStream("f", new StringReader("cba"));
    stream.reset();
    assertTokenStream(stream, "c,0,1,1/b,1,2,1/a,2,3,1");
  }
  
  @Test
  public void testMultipleSynonymsExpand() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(1, true, "a,aa/b,bb");
    TokenStream stream = a.tokenStream("f", new StringReader("ababb"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1/bb,1,2,0/a,2,3,1/aa,2,3,0/bb,3,5,1/b,3,5,0");

    a = new NGramSynonymTokenizerTestAnalyzer(1, true, "a,aa/b,bb/c,cc");
    stream = a.tokenStream("f", new StringReader("cba"));
    stream.reset();
    assertTokenStream(stream, "c,0,1,1/cc,0,1,0/b,1,2,1/bb,1,2,0/a,2,3,1/aa,2,3,0");
  }
  
  @Test
  public void testPrevStrSingleSynonym1() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(1, false, "a,aa");
    TokenStream stream = a.tokenStream("f", new StringReader("ba"));
    stream.reset();
    assertTokenStream(stream, "b,0,1,1/a,1,2,1");

    a = new NGramSynonymTokenizerTestAnalyzer(1, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("bba"));
    stream.reset();
    assertTokenStream(stream, "b,0,1,1/b,1,2,1/a,2,3,1");

    a = new NGramSynonymTokenizerTestAnalyzer(1, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("dcba"));
    stream.reset();
    assertTokenStream(stream, "d,0,1,1/c,1,2,1/b,2,3,1/a,3,4,1");

    a = new NGramSynonymTokenizerTestAnalyzer(1, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("edcba"));
    stream.reset();
    assertTokenStream(stream, "e,0,1,1/d,1,2,1/c,2,3,1/b,3,4,1/a,4,5,1");
  }

  @Test
  public void testPrevStrSingleSynonym2() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(2, false, "a,aa");
    TokenStream stream = a.tokenStream("f", new StringReader("ba"));
    stream.reset();
    assertTokenStream(stream, "b,0,1,1/a,1,2,1");

    a = new NGramSynonymTokenizerTestAnalyzer(2, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("bba"));
    stream.reset();
    assertTokenStream(stream, "bb,0,2,1/a,2,3,1");

    a = new NGramSynonymTokenizerTestAnalyzer(2, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("dcba"));
    stream.reset();
    assertTokenStream(stream, "dc,0,2,1/cb,1,3,1/a,3,4,1");

    a = new NGramSynonymTokenizerTestAnalyzer(2, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("edcba"));
    stream.reset();
    assertTokenStream(stream, "ed,0,2,1/dc,1,3,1/cb,2,4,1/a,4,5,1");
  }

  @Test
  public void testPrevStrSingleSynonym3() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(3, false, "a,aa");
    TokenStream stream = a.tokenStream("f", new StringReader("ba"));
    stream.reset();
    assertTokenStream(stream, "b,0,1,1/a,1,2,1");

    a = new NGramSynonymTokenizerTestAnalyzer(3, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("bba"));
    stream.reset();
    assertTokenStream(stream, "bb,0,2,1/a,2,3,1");

    a = new NGramSynonymTokenizerTestAnalyzer(3, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("dcba"));
    stream.reset();
    assertTokenStream(stream, "dcb,0,3,1/a,3,4,1");

    a = new NGramSynonymTokenizerTestAnalyzer(3, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("edcba"));
    stream.reset();
    assertTokenStream(stream, "edc,0,3,1/dcb,1,4,1/a,4,5,1");
  }

  @Test
  public void testPrevStrSingleSynonym4() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(4, false, "a,aa");
    TokenStream stream = a.tokenStream("f", new StringReader("ba"));
    stream.reset();
    assertTokenStream(stream, "b,0,1,1/a,1,2,1");

    a = new NGramSynonymTokenizerTestAnalyzer(4, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("bba"));
    stream.reset();
    assertTokenStream(stream, "bb,0,2,1/a,2,3,1");

    a = new NGramSynonymTokenizerTestAnalyzer(4, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("dcba"));
    stream.reset();
    assertTokenStream(stream, "dcb,0,3,1/a,3,4,1");

    a = new NGramSynonymTokenizerTestAnalyzer(4, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("edcba"));
    stream.reset();
    assertTokenStream(stream, "edcb,0,4,1/a,4,5,1");

    a = new NGramSynonymTokenizerTestAnalyzer(4, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("fedcba"));
    stream.reset();
    assertTokenStream(stream, "fedc,0,4,1/edcb,1,5,1/a,5,6,1");
  }

  @Test
  public void testPrevStrSingleSynonymExpand1() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(1, true, "a,aa");
    TokenStream stream = a.tokenStream("f", new StringReader("ba"));
    stream.reset();
    assertTokenStream(stream, "b,0,1,1/a,1,2,1/aa,1,2,0");

    a = new NGramSynonymTokenizerTestAnalyzer(1, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("bba"));
    stream.reset();
    assertTokenStream(stream, "b,0,1,1/b,1,2,1/a,2,3,1/aa,2,3,0");

    a = new NGramSynonymTokenizerTestAnalyzer(1, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("dcba"));
    stream.reset();
    assertTokenStream(stream, "d,0,1,1/c,1,2,1/b,2,3,1/a,3,4,1/aa,3,4,0");

    a = new NGramSynonymTokenizerTestAnalyzer(1, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("edcba"));
    stream.reset();
    assertTokenStream(stream, "e,0,1,1/d,1,2,1/c,2,3,1/b,3,4,1/a,4,5,1/aa,4,5,0");
  }

  @Test
  public void testPrevStrSingleSynonymExpand2() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(2, true, "a,aa");
    TokenStream stream = a.tokenStream("f", new StringReader("ba"));
    stream.reset();
    assertTokenStream(stream, "b,0,1,1/a,1,2,1/aa,1,2,0");

    a = new NGramSynonymTokenizerTestAnalyzer(2, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("bba"));
    stream.reset();
    assertTokenStream(stream, "bb,0,2,1/b,1,2,0/a,2,3,1/aa,2,3,0");

    a = new NGramSynonymTokenizerTestAnalyzer(2, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("dcba"));
    stream.reset();
    assertTokenStream(stream, "dc,0,2,1/cb,1,3,1/b,2,3,0/a,3,4,1/aa,3,4,0");

    a = new NGramSynonymTokenizerTestAnalyzer(2, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("edcba"));
    stream.reset();
    assertTokenStream(stream, "ed,0,2,1/dc,1,3,1/cb,2,4,1/b,3,4,0/a,4,5,1/aa,4,5,0");
  }

  @Test
  public void testPrevStrSingleSynonymExpand3() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(3, true, "a,aa");
    TokenStream stream = a.tokenStream("f", new StringReader("ba"));
    stream.reset();
    assertTokenStream(stream, "b,0,1,1/a,1,2,1/aa,1,2,0");

    a = new NGramSynonymTokenizerTestAnalyzer(3, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("bba"));
    stream.reset();
    assertTokenStream(stream, "bb,0,2,1/b,1,2,0/a,2,3,1/aa,2,3,0");

    a = new NGramSynonymTokenizerTestAnalyzer(3, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("dcba"));
    stream.reset();
    assertTokenStream(stream, "dcb,0,3,1/cb,1,3,0/b,2,3,0/a,3,4,1/aa,3,4,0");

    a = new NGramSynonymTokenizerTestAnalyzer(3, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("edcba"));
    stream.reset();
    assertTokenStream(stream, "edc,0,3,1/dcb,1,4,1/cb,2,4,0/b,3,4,0/a,4,5,1/aa,4,5,0");
  }
  
  @Test
  public void testPrevStrSingleSynonymExpand4() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(4, true, "a,aa");
    TokenStream stream = a.tokenStream("f", new StringReader("ba"));
    stream.reset();
    assertTokenStream(stream, "b,0,1,1/a,1,2,1/aa,1,2,0");

    a = new NGramSynonymTokenizerTestAnalyzer(4, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("bba"));
    stream.reset();
    assertTokenStream(stream, "bb,0,2,1/b,1,2,0/a,2,3,1/aa,2,3,0");

    a = new NGramSynonymTokenizerTestAnalyzer(4, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("dcba"));
    stream.reset();
    assertTokenStream(stream, "dcb,0,3,1/cb,1,3,0/b,2,3,0/a,3,4,1/aa,3,4,0");

    a = new NGramSynonymTokenizerTestAnalyzer(4, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("edcba"));
    stream.reset();
    assertTokenStream(stream, "edcb,0,4,1/dcb,1,4,0/cb,2,4,0/b,3,4,0/a,4,5,1/aa,4,5,0");

    a = new NGramSynonymTokenizerTestAnalyzer(4, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("fedcba"));
    stream.reset();
    assertTokenStream(stream, "fedc,0,4,1/edcb,1,5,1/dcb,2,5,0/cb,3,5,0/b,4,5,0/a,5,6,1/aa,5,6,0");
  }

  @Test
  public void testAfterStrSingleSynonym1() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(1, false, "a,aa");
    TokenStream stream = a.tokenStream("f", new StringReader("ab"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/b,1,2,1");

    a = new NGramSynonymTokenizerTestAnalyzer(1, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("abb"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/b,1,2,1/b,2,3,1");

    a = new NGramSynonymTokenizerTestAnalyzer(1, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcd"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/b,1,2,1/c,2,3,1/d,3,4,1");

    a = new NGramSynonymTokenizerTestAnalyzer(1, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcde"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/b,1,2,1/c,2,3,1/d,3,4,1/e,4,5,1");
  }

  @Test
  public void testAfterStrSingleSynonym2() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(2, false, "a,aa");
    TokenStream stream = a.tokenStream("f", new StringReader("ab"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/b,1,2,1");

    a = new NGramSynonymTokenizerTestAnalyzer(2, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("abb"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/bb,1,3,1");

    a = new NGramSynonymTokenizerTestAnalyzer(2, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcd"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/bc,1,3,1/cd,2,4,1");

    a = new NGramSynonymTokenizerTestAnalyzer(2, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcde"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/bc,1,3,1/cd,2,4,1/de,3,5,1");
  }

  @Test
  public void testAfterStrSingleSynonym3() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(3, false, "a,aa");
    TokenStream stream = a.tokenStream("f", new StringReader("ab"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/b,1,2,1");

    a = new NGramSynonymTokenizerTestAnalyzer(3, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("abb"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/bb,1,3,1");

    a = new NGramSynonymTokenizerTestAnalyzer(3, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcd"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/bcd,1,4,1");

    a = new NGramSynonymTokenizerTestAnalyzer(3, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcde"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/bcd,1,4,1/cde,2,5,1");
  }

  @Test
  public void testAfterStrSingleSynonym4() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(4, false, "a,aa");
    TokenStream stream = a.tokenStream("f", new StringReader("ab"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/b,1,2,1");

    a = new NGramSynonymTokenizerTestAnalyzer(4, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("abb"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/bb,1,3,1");

    a = new NGramSynonymTokenizerTestAnalyzer(4, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcd"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/bcd,1,4,1");

    a = new NGramSynonymTokenizerTestAnalyzer(4, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcde"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/bcde,1,5,1");

    a = new NGramSynonymTokenizerTestAnalyzer(4, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcdef"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/bcde,1,5,1/cdef,2,6,1");
  }

  @Test
  public void testAfterStrSingleSynonymExpand1() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(1, true, "a,aa");
    TokenStream stream = a.tokenStream("f", new StringReader("ab"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1");

    a = new NGramSynonymTokenizerTestAnalyzer(1, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("abb"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1/b,2,3,1");

    a = new NGramSynonymTokenizerTestAnalyzer(1, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcd"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1/c,2,3,1/d,3,4,1");

    a = new NGramSynonymTokenizerTestAnalyzer(1, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcde"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1/c,2,3,1/d,3,4,1/e,4,5,1");
  }

  @Test
  public void testAfterStrSingleSynonymExpand2() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(2, true, "a,aa");
    TokenStream stream = a.tokenStream("f", new StringReader("ab"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1");

    a = new NGramSynonymTokenizerTestAnalyzer(2, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("abb"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1/bb,1,3,0");

    a = new NGramSynonymTokenizerTestAnalyzer(2, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcd"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1/bc,1,3,0/cd,2,4,1");

    a = new NGramSynonymTokenizerTestAnalyzer(2, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcde"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1/bc,1,3,0/cd,2,4,1/de,3,5,1");
  }
  
  @Test
  public void testAfterStrSingleSynonymExpand3() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(3, true, "a,aa");
    TokenStream stream = a.tokenStream("f", new StringReader("ab"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1");

    a = new NGramSynonymTokenizerTestAnalyzer(3, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("abb"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1/bb,1,3,0");

    a = new NGramSynonymTokenizerTestAnalyzer(3, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcd"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1/bc,1,3,0/bcd,1,4,0");

    a = new NGramSynonymTokenizerTestAnalyzer(3, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcde"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1/bc,1,3,0/bcd,1,4,0/cde,2,5,1");
  }
  
  @Test
  public void testAfterStrSingleSynonymExpand4() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(4, true, "a,aa");
    TokenStream stream = a.tokenStream("f", new StringReader("ab"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1");

    a = new NGramSynonymTokenizerTestAnalyzer(4, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("abb"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1/bb,1,3,0");

    a = new NGramSynonymTokenizerTestAnalyzer(4, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcd"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1/bc,1,3,0/bcd,1,4,0");

    a = new NGramSynonymTokenizerTestAnalyzer(4, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcde"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1/bc,1,3,0/bcd,1,4,0/bcde,1,5,0");

    a = new NGramSynonymTokenizerTestAnalyzer(4, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcdef"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1/bc,1,3,0/bcd,1,4,0/bcde,1,5,0/cdef,2,6,1");
  }
  
  @Test
  public void testSandwichStr1() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(1, false, "a,aa");
    TokenStream stream = a.tokenStream("f", new StringReader("aba"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/b,1,2,1/a,2,3,1");

    a = new NGramSynonymTokenizerTestAnalyzer(1, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("abba"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/b,1,2,1/b,2,3,1/a,3,4,1");

    a = new NGramSynonymTokenizerTestAnalyzer(1, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcda"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/b,1,2,1/c,2,3,1/d,3,4,1/a,4,5,1");

    a = new NGramSynonymTokenizerTestAnalyzer(1, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcdea"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/b,1,2,1/c,2,3,1/d,3,4,1/e,4,5,1/a,5,6,1");
  }

  @Test
  public void testSandwichStr2() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(2, false, "a,aa");
    TokenStream stream = a.tokenStream("f", new StringReader("aba"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/b,1,2,1/a,2,3,1");

    a = new NGramSynonymTokenizerTestAnalyzer(2, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("abba"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/bb,1,3,1/a,3,4,1");

    a = new NGramSynonymTokenizerTestAnalyzer(2, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcda"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/bc,1,3,1/cd,2,4,1/a,4,5,1");

    a = new NGramSynonymTokenizerTestAnalyzer(2, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcdea"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/bc,1,3,1/cd,2,4,1/de,3,5,1/a,5,6,1");
  }

  @Test
  public void testSandwichStr3() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(3, false, "a,aa");
    TokenStream stream = a.tokenStream("f", new StringReader("aba"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/b,1,2,1/a,2,3,1");

    a = new NGramSynonymTokenizerTestAnalyzer(3, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("abba"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/bb,1,3,1/a,3,4,1");

    a = new NGramSynonymTokenizerTestAnalyzer(3, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcda"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/bcd,1,4,1/a,4,5,1");

    a = new NGramSynonymTokenizerTestAnalyzer(3, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcdea"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/bcd,1,4,1/cde,2,5,1/a,5,6,1");

    a = new NGramSynonymTokenizerTestAnalyzer(3, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcdefa"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/bcd,1,4,1/cde,2,5,1/def,3,6,1/a,6,7,1");
  }

  @Test
  public void testSandwichStr4() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(4, false, "a,aa");
    TokenStream stream = a.tokenStream("f", new StringReader("aba"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/b,1,2,1/a,2,3,1");

    a = new NGramSynonymTokenizerTestAnalyzer(4, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("abba"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/bb,1,3,1/a,3,4,1");

    a = new NGramSynonymTokenizerTestAnalyzer(4, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcda"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/bcd,1,4,1/a,4,5,1");

    a = new NGramSynonymTokenizerTestAnalyzer(4, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcdea"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/bcde,1,5,1/a,5,6,1");

    a = new NGramSynonymTokenizerTestAnalyzer(4, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcdefa"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/bcde,1,5,1/cdef,2,6,1/a,6,7,1");
  }

  @Test
  public void testSandwichStrExpand1() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(1, true, "a,aa");
    TokenStream stream = a.tokenStream("f", new StringReader("aba"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1/a,2,3,1/aa,2,3,0");

    a = new NGramSynonymTokenizerTestAnalyzer(1, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("abba"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1/b,2,3,1/a,3,4,1/aa,3,4,0");

    a = new NGramSynonymTokenizerTestAnalyzer(1, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcda"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1/c,2,3,1/d,3,4,1/a,4,5,1/aa,4,5,0");

    a = new NGramSynonymTokenizerTestAnalyzer(1, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcdea"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1/c,2,3,1/d,3,4,1/e,4,5,1/a,5,6,1/aa,5,6,0");
  }

  @Test
  public void testSandwichStrExpand2() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(2, true, "a,aa");
    TokenStream stream = a.tokenStream("f", new StringReader("aba"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1/a,2,3,1/aa,2,3,0");

    a = new NGramSynonymTokenizerTestAnalyzer(2, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("abba"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1/bb,1,3,0/b,2,3,0/a,3,4,1/aa,3,4,0");

    a = new NGramSynonymTokenizerTestAnalyzer(2, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcda"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1/bc,1,3,0/cd,2,4,1/d,3,4,0/a,4,5,1/aa,4,5,0");

    a = new NGramSynonymTokenizerTestAnalyzer(2, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcdea"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1/bc,1,3,0/cd,2,4,1/de,3,5,1/e,4,5,0/a,5,6,1/aa,5,6,0");
  }

  @Test
  public void testSandwichStrExpand3() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(3, true, "a,aa");
    TokenStream stream = a.tokenStream("f", new StringReader("aba"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1/a,2,3,1/aa,2,3,0");

    a = new NGramSynonymTokenizerTestAnalyzer(3, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("abba"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1/bb,1,3,0/b,2,3,0/a,3,4,1/aa,3,4,0");

    a = new NGramSynonymTokenizerTestAnalyzer(3, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcda"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1/bc,1,3,0/bcd,1,4,0/cd,2,4,0/d,3,4,0/a,4,5,1/aa,4,5,0");

    a = new NGramSynonymTokenizerTestAnalyzer(3, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcdea"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1/bc,1,3,0/bcd,1,4,0/cde,2,5,1/de,3,5,0/e,4,5,0/a,5,6,1/aa,5,6,0");

    a = new NGramSynonymTokenizerTestAnalyzer(3, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcdefa"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1/bc,1,3,0/bcd,1,4,0/cde,2,5,1/def,3,6,1/ef,4,6,0/f,5,6,0/a,6,7,1/aa,6,7,0");
  }

  @Test
  public void testSandwichStrExpand4() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(4, true, "a,aa");
    TokenStream stream = a.tokenStream("f", new StringReader("aba"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1/a,2,3,1/aa,2,3,0");

    a = new NGramSynonymTokenizerTestAnalyzer(4, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("abba"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1/bb,1,3,0/b,2,3,0/a,3,4,1/aa,3,4,0");

    a = new NGramSynonymTokenizerTestAnalyzer(4, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcda"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1/bc,1,3,0/bcd,1,4,0/cd,2,4,0/d,3,4,0/a,4,5,1/aa,4,5,0");

    a = new NGramSynonymTokenizerTestAnalyzer(4, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcdea"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1/bc,1,3,0/bcd,1,4,0/bcde,1,5,0/cde,2,5,0/de,3,5,0/e,4,5,0/a,5,6,1/aa,5,6,0");

    a = new NGramSynonymTokenizerTestAnalyzer(4, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("abcdefa"));
    stream.reset();
    assertTokenStream(stream, "a,0,1,1/aa,0,1,0/b,1,2,1/bc,1,3,0/bcd,1,4,0/bcde,1,5,0/cdef,2,6,1/def,3,6,0/ef,4,6,0/f,5,6,0/a,6,7,1/aa,6,7,0");
  }
  
  @Test
  public void testSandwichSynonym1() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(1, false, "a,aa");
    TokenStream stream = a.tokenStream("f", new StringReader("bab"));
    stream.reset();
    assertTokenStream(stream, "b,0,1,1/a,1,2,1/b,2,3,1");

    a = new NGramSynonymTokenizerTestAnalyzer(1, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("bbabb"));
    stream.reset();
    assertTokenStream(stream, "b,0,1,1/b,1,2,1/a,2,3,1/b,3,4,1/b,4,5,1");

    a = new NGramSynonymTokenizerTestAnalyzer(1, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("dcbabcd"));
    stream.reset();
    assertTokenStream(stream, "d,0,1,1/c,1,2,1/b,2,3,1/a,3,4,1/b,4,5,1/c,5,6,1/d,6,7,1");

    a = new NGramSynonymTokenizerTestAnalyzer(1, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("edcbabcde"));
    stream.reset();
    assertTokenStream(stream, "e,0,1,1/d,1,2,1/c,2,3,1/b,3,4,1/a,4,5,1/b,5,6,1/c,6,7,1/d,7,8,1/e,8,9,1");
  }

  @Test
  public void testSandwichSynonym2() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(2, false, "a,aa");
    TokenStream stream = a.tokenStream("f", new StringReader("bab"));
    stream.reset();
    assertTokenStream(stream, "b,0,1,1/a,1,2,1/b,2,3,1");

    a = new NGramSynonymTokenizerTestAnalyzer(2, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("bbabb"));
    stream.reset();
    assertTokenStream(stream, "bb,0,2,1/a,2,3,1/bb,3,5,1");

    a = new NGramSynonymTokenizerTestAnalyzer(2, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("dcbabcd"));
    stream.reset();
    assertTokenStream(stream, "dc,0,2,1/cb,1,3,1/a,3,4,1/bc,4,6,1/cd,5,7,1");

    a = new NGramSynonymTokenizerTestAnalyzer(2, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("edcbabcde"));
    stream.reset();
    assertTokenStream(stream, "ed,0,2,1/dc,1,3,1/cb,2,4,1/a,4,5,1/bc,5,7,1/cd,6,8,1/de,7,9,1");
  }

  @Test
  public void testSandwichSynonym3() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(3, false, "a,aa");
    TokenStream stream = a.tokenStream("f", new StringReader("bab"));
    stream.reset();
    assertTokenStream(stream, "b,0,1,1/a,1,2,1/b,2,3,1");

    a = new NGramSynonymTokenizerTestAnalyzer(3, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("bbabb"));
    stream.reset();
    assertTokenStream(stream, "bb,0,2,1/a,2,3,1/bb,3,5,1");

    a = new NGramSynonymTokenizerTestAnalyzer(3, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("dcbabcd"));
    stream.reset();
    assertTokenStream(stream, "dcb,0,3,1/a,3,4,1/bcd,4,7,1");

    a = new NGramSynonymTokenizerTestAnalyzer(3, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("edcbabcde"));
    stream.reset();
    assertTokenStream(stream, "edc,0,3,1/dcb,1,4,1/a,4,5,1/bcd,5,8,1/cde,6,9,1");

    a = new NGramSynonymTokenizerTestAnalyzer(3, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("fedcbabcdef"));
    stream.reset();
    assertTokenStream(stream, "fed,0,3,1/edc,1,4,1/dcb,2,5,1/a,5,6,1/bcd,6,9,1/cde,7,10,1/def,8,11,1");
  }

  @Test
  public void testSandwichSynonym4() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(4, false, "a,aa");
    TokenStream stream = a.tokenStream("f", new StringReader("bab"));
    stream.reset();
    assertTokenStream(stream, "b,0,1,1/a,1,2,1/b,2,3,1");

    a = new NGramSynonymTokenizerTestAnalyzer(4, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("bbabb"));
    stream.reset();
    assertTokenStream(stream, "bb,0,2,1/a,2,3,1/bb,3,5,1");

    a = new NGramSynonymTokenizerTestAnalyzer(4, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("dcbabcd"));
    stream.reset();
    assertTokenStream(stream, "dcb,0,3,1/a,3,4,1/bcd,4,7,1");

    a = new NGramSynonymTokenizerTestAnalyzer(4, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("edcbabcde"));
    stream.reset();
    assertTokenStream(stream, "edcb,0,4,1/a,4,5,1/bcde,5,9,1");

    a = new NGramSynonymTokenizerTestAnalyzer(4, false, "a,aa");
    stream = a.tokenStream("f", new StringReader("fedcbabcdef"));
    stream.reset();
    assertTokenStream(stream, "fedc,0,4,1/edcb,1,5,1/a,5,6,1/bcde,6,10,1/cdef,7,11,1");
  }
  
  @Test
  public void testSandwichSynonymExpand1() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(1, true, "a,aa");
    TokenStream stream = a.tokenStream("f", new StringReader("bab"));
    stream.reset();
    assertTokenStream(stream, "b,0,1,1/a,1,2,1/aa,1,2,0/b,2,3,1");

    a = new NGramSynonymTokenizerTestAnalyzer(1, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("bbabb"));
    stream.reset();
    assertTokenStream(stream, "b,0,1,1/b,1,2,1/a,2,3,1/aa,2,3,0/b,3,4,1/b,4,5,1");

    a = new NGramSynonymTokenizerTestAnalyzer(1, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("dcbabcd"));
    stream.reset();
    assertTokenStream(stream, "d,0,1,1/c,1,2,1/b,2,3,1/a,3,4,1/aa,3,4,0/b,4,5,1/c,5,6,1/d,6,7,1");

    a = new NGramSynonymTokenizerTestAnalyzer(1, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("edcbabcde"));
    stream.reset();
    assertTokenStream(stream, "e,0,1,1/d,1,2,1/c,2,3,1/b,3,4,1/a,4,5,1/aa,4,5,0/b,5,6,1/c,6,7,1/d,7,8,1/e,8,9,1");
  }

  @Test
  public void testSandwichSynonymExpand2() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(2, true, "a,aa");
    TokenStream stream = a.tokenStream("f", new StringReader("bab"));
    stream.reset();
    assertTokenStream(stream, "b,0,1,1/a,1,2,1/aa,1,2,0/b,2,3,1");

    a = new NGramSynonymTokenizerTestAnalyzer(2, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("bbabb"));
    stream.reset();
    assertTokenStream(stream, "bb,0,2,1/b,1,2,0/a,2,3,1/aa,2,3,0/b,3,4,1/bb,3,5,0");

    a = new NGramSynonymTokenizerTestAnalyzer(2, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("dcbabcd"));
    stream.reset();
    assertTokenStream(stream, "dc,0,2,1/cb,1,3,1/b,2,3,0/a,3,4,1/aa,3,4,0/b,4,5,1/bc,4,6,0/cd,5,7,1");

    a = new NGramSynonymTokenizerTestAnalyzer(2, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("edcbabcde"));
    stream.reset();
    assertTokenStream(stream, "ed,0,2,1/dc,1,3,1/cb,2,4,1/b,3,4,0/a,4,5,1/aa,4,5,0/b,5,6,1/bc,5,7,0/cd,6,8,1/de,7,9,1");
  }

  @Test
  public void testSandwichSynonymExpand3() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(3, true, "a,aa");
    TokenStream stream = a.tokenStream("f", new StringReader("bab"));
    stream.reset();
    assertTokenStream(stream, "b,0,1,1/a,1,2,1/aa,1,2,0/b,2,3,1");

    a = new NGramSynonymTokenizerTestAnalyzer(3, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("bbabb"));
    stream.reset();
    assertTokenStream(stream, "bb,0,2,1/b,1,2,0/a,2,3,1/aa,2,3,0/b,3,4,1/bb,3,5,0");

    a = new NGramSynonymTokenizerTestAnalyzer(3, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("dcbabcd"));
    stream.reset();
    assertTokenStream(stream, "dcb,0,3,1/cb,1,3,0/b,2,3,0/a,3,4,1/aa,3,4,0/b,4,5,1/bc,4,6,0/bcd,4,7,0");

    a = new NGramSynonymTokenizerTestAnalyzer(3, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("edcbabcde"));
    stream.reset();
    assertTokenStream(stream, "edc,0,3,1/dcb,1,4,1/cb,2,4,0/b,3,4,0/a,4,5,1/aa,4,5,0/b,5,6,1/bc,5,7,0/bcd,5,8,0/cde,6,9,1");

    a = new NGramSynonymTokenizerTestAnalyzer(3, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("fedcbabcdef"));
    stream.reset();
    assertTokenStream(stream, "fed,0,3,1/edc,1,4,1/dcb,2,5,1/cb,3,5,0/b,4,5,0/a,5,6,1/aa,5,6,0/b,6,7,1/bc,6,8,0/bcd,6,9,0/cde,7,10,1/def,8,11,1");
  }

  @Test
  public void testSandwichSynonymExpand4() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(4, true, "a,aa");
    TokenStream stream = a.tokenStream("f", new StringReader("bab"));
    stream.reset();
    assertTokenStream(stream, "b,0,1,1/a,1,2,1/aa,1,2,0/b,2,3,1");

    a = new NGramSynonymTokenizerTestAnalyzer(4, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("bbabb"));
    stream.reset();
    assertTokenStream(stream, "bb,0,2,1/b,1,2,0/a,2,3,1/aa,2,3,0/b,3,4,1/bb,3,5,0");

    a = new NGramSynonymTokenizerTestAnalyzer(4, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("dcbabcd"));
    stream.reset();
    assertTokenStream(stream, "dcb,0,3,1/cb,1,3,0/b,2,3,0/a,3,4,1/aa,3,4,0/b,4,5,1/bc,4,6,0/bcd,4,7,0");

    a = new NGramSynonymTokenizerTestAnalyzer(4, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("edcbabcde"));
    stream.reset();
    assertTokenStream(stream, "edcb,0,4,1/dcb,1,4,0/cb,2,4,0/b,3,4,0/a,4,5,1/aa,4,5,0/b,5,6,1/bc,5,7,0/bcd,5,8,0/bcde,5,9,0");

    a = new NGramSynonymTokenizerTestAnalyzer(4, true, "a,aa");
    stream = a.tokenStream("f", new StringReader("fedcbabcdef"));
    stream.reset();
    assertTokenStream(stream, "fedc,0,4,1/edcb,1,5,1/dcb,2,5,0/cb,3,5,0/b,4,5,0/a,5,6,1/aa,5,6,0/b,6,7,1/bc,6,8,0/bcd,6,9,0/bcde,6,10,0/cdef,7,11,1");
  }
  
  @Test
  public void testComplex1() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(1, false, "a,aa/b,bb");
    TokenStream stream = a.tokenStream("f", new StringReader("cabca"));
    stream.reset();
    assertTokenStream(stream, "c,0,1,1/a,1,2,1/b,2,3,1/c,3,4,1/a,4,5,1");

    a = new NGramSynonymTokenizerTestAnalyzer(1, false, "a,aa/b,bb");
    stream = a.tokenStream("f", new StringReader("ccabcca"));
    stream.reset();
    assertTokenStream(stream, "c,0,1,1/c,1,2,1/a,2,3,1/b,3,4,1/c,4,5,1/c,5,6,1/a,6,7,1");

    a = new NGramSynonymTokenizerTestAnalyzer(1, false, "a,aa/b,bb");
    stream = a.tokenStream("f", new StringReader("edcabcdea"));
    stream.reset();
    assertTokenStream(stream, "e,0,1,1/d,1,2,1/c,2,3,1/a,3,4,1/b,4,5,1/c,5,6,1/d,6,7,1/e,7,8,1/a,8,9,1");

    a = new NGramSynonymTokenizerTestAnalyzer(1, false, "a,aa/b,bb");
    stream = a.tokenStream("f", new StringReader("fedcabcdefa"));
    stream.reset();
    assertTokenStream(stream, "f,0,1,1/e,1,2,1/d,2,3,1/c,3,4,1/a,4,5,1/b,5,6,1/c,6,7,1/d,7,8,1/e,8,9,1/f,9,10,1/a,10,11,1");
  }

  @Test
  public void testComplex2() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(2, false, "a,aa/b,bb");
    TokenStream stream = a.tokenStream("f", new StringReader("cabca"));
    stream.reset();
    assertTokenStream(stream, "c,0,1,1/a,1,2,1/b,2,3,1/c,3,4,1/a,4,5,1");

    a = new NGramSynonymTokenizerTestAnalyzer(2, false, "a,aa/b,bb");
    stream = a.tokenStream("f", new StringReader("ccabcca"));
    stream.reset();
    assertTokenStream(stream, "cc,0,2,1/a,2,3,1/b,3,4,1/cc,4,6,1/a,6,7,1");

    a = new NGramSynonymTokenizerTestAnalyzer(2, false, "a,aa/b,bb");
    stream = a.tokenStream("f", new StringReader("edcabcdea"));
    stream.reset();
    assertTokenStream(stream, "ed,0,2,1/dc,1,3,1/a,3,4,1/b,4,5,1/cd,5,7,1/de,6,8,1/a");

    a = new NGramSynonymTokenizerTestAnalyzer(2, false, "a,aa/b,bb");
    stream = a.tokenStream("f", new StringReader("fedcabcdefa"));
    stream.reset();
    assertTokenStream(stream, "fe,0,2,1/ed,1,3,1/dc,2,4,1/a,4,5,1/b,5,6,1/cd,6,8,1/de,7,9,1/ef,8,10,1/a,10,11,1");
  }

  @Test
  public void testComplex3() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(3, false, "a,aa/b,bb");
    TokenStream stream = a.tokenStream("f", new StringReader("cabca"));
    stream.reset();
    assertTokenStream(stream, "c,0,1,1/a,1,2,1/b,2,3,1/c,3,4,1/a,4,5,1");

    a = new NGramSynonymTokenizerTestAnalyzer(3, false, "a,aa/b,bb");
    stream = a.tokenStream("f", new StringReader("ccabcca"));
    stream.reset();
    assertTokenStream(stream, "cc,0,2,1/a,2,3,1/b,3,4,1/cc,4,6,1/a,6,7,1");

    a = new NGramSynonymTokenizerTestAnalyzer(3, false, "a,aa/b,bb");
    stream = a.tokenStream("f", new StringReader("edcabcdea"));
    stream.reset();
    assertTokenStream(stream, "edc,0,3,1/a,3,4,1/b,4,5,1/cde,5,8,1/a,8,9,1");

    a = new NGramSynonymTokenizerTestAnalyzer(3, false, "a,aa/b,bb");
    stream = a.tokenStream("f", new StringReader("fedcabcdefa"));
    stream.reset();
    assertTokenStream(stream, "fed,0,3,1/edc,1,4,1/a,4,5,1/b,5,6,1/cde,6,9,1/def,7,10,1/a,10,11,1");

    a = new NGramSynonymTokenizerTestAnalyzer(3, false, "a,aa/b,bb");
    stream = a.tokenStream("f", new StringReader("gfedcabcdefga"));
    stream.reset();
    assertTokenStream(stream, "gfe,0,3,1/fed,1,4,1/edc,2,5,1/a,5,6,1/b,6,7,1/cde,7,10,1/def,8,11,1/efg,9,12,1/a,12,13,1");
  }

  @Test
  public void testComplex4() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(4, false, "a,aa/b,bb");
    TokenStream stream = a.tokenStream("f", new StringReader("cabca"));
    stream.reset();
    assertTokenStream(stream, "c,0,1,1/a,1,2,1/b,2,3,1/c,3,4,1/a,4,5,1");

    a = new NGramSynonymTokenizerTestAnalyzer(4, false, "a,aa/b,bb");
    stream = a.tokenStream("f", new StringReader("ccabcca"));
    stream.reset();
    assertTokenStream(stream, "cc,0,2,1/a,2,3,1/b,3,4,1/cc,4,6,1/a,6,7,1");

    a = new NGramSynonymTokenizerTestAnalyzer(4, false, "a,aa/b,bb");
    stream = a.tokenStream("f", new StringReader("edcabcdea"));
    stream.reset();
    assertTokenStream(stream, "edc,0,3,1/a,3,4,1/b,4,5,1/cde,5,8,1/a,8,9,1");

    a = new NGramSynonymTokenizerTestAnalyzer(4, false, "a,aa/b,bb");
    stream = a.tokenStream("f", new StringReader("fedcabcdefa"));
    stream.reset();
    assertTokenStream(stream, "fedc,0,4,1/a,4,5,1/b,5,6,1/cdef,6,10,1/a,10,11,1");

    a = new NGramSynonymTokenizerTestAnalyzer(4, false, "a,aa/b,bb");
    stream = a.tokenStream("f", new StringReader("gfedcabcdefga"));
    stream.reset();
    assertTokenStream(stream, "gfed,0,4,1/fedc,1,5,1/a,5,6,1/b,6,7,1/cdef,7,11,1/defg,8,12,1/a,12,13,1");
  }
  
  @Test
  public void testComplexExpand1() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(1, true, "a,aa/b,bb");
    TokenStream stream = a.tokenStream("f", new StringReader("cabca"));
    stream.reset();
    assertTokenStream(stream, "c,0,1,1/a,1,2,1/aa,1,2,0/b,2,3,1/bb,2,3,0/c,3,4,1/a,4,5,1/aa,4,5,0");

    a = new NGramSynonymTokenizerTestAnalyzer(1, true, "a,aa/b,bb");
    stream = a.tokenStream("f", new StringReader("ccabcca"));
    stream.reset();
    assertTokenStream(stream, "c,0,1,1/c,1,2,1/a,2,3,1/aa,2,3,0/b,3,4,1/bb,3,4,0/c,4,5,1/c,5,6,1/a,6,7,1/aa,6,7,0");

    a = new NGramSynonymTokenizerTestAnalyzer(1, true, "a,aa/b,bb");
    stream = a.tokenStream("f", new StringReader("edcabcdea"));
    stream.reset();
    assertTokenStream(stream, "e,0,1,1/d,1,2,1/c,2,3,1/a,3,4,1/aa,3,4,0/b,4,5,1/bb,4,5,0/c,5,6,1/d,6,7,1/e,7,8,1/a,8,9,1/aa,8,9,0");

    a = new NGramSynonymTokenizerTestAnalyzer(1, true, "a,aa/b,bb");
    stream = a.tokenStream("f", new StringReader("fedcabcdefa"));
    stream.reset();
    assertTokenStream(stream, "f,0,1,1/e,1,2,1/d,2,3,1/c,3,4,1/a,4,5,1/aa,4,5,0/b,5,6,1/bb,5,6,0/c,6,7,1/d,7,8,1/e,8,9,1/f,9,10,1/a,10,11,1/aa,10,11,0");
  }

  @Test
  public void testComplexExpand2() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(2, true, "a,aa/b,bb");
    TokenStream stream = a.tokenStream("f", new StringReader("cabca"));
    stream.reset();
    assertTokenStream(stream, "c,0,1,1/a,1,2,1/aa,1,2,0/b,2,3,1/bb,2,3,0/c,3,4,1/a,4,5,1/aa,4,5,0");

    a = new NGramSynonymTokenizerTestAnalyzer(2, true, "a,aa/b,bb");
    stream = a.tokenStream("f", new StringReader("ccabcca"));
    stream.reset();
    assertTokenStream(stream, "cc,0,2,1/c,1,2,0/a,2,3,1/aa,2,3,0/b,3,4,1/bb,3,4,0/c,4,5,1/cc,4,6,0/c,5,6,0/a,6,7,1/aa,6,7,0");

    a = new NGramSynonymTokenizerTestAnalyzer(2, true, "a,aa/b,bb");
    stream = a.tokenStream("f", new StringReader("edcabcdea"));
    stream.reset();
    assertTokenStream(stream, "ed,0,2,1/dc,1,3,1/c,2,3,0/a,3,4,1/aa,3,4,0/b,4,5,1/bb,4,5,0/c,5,6,1/cd,5,7,0/de,6,8,1/e,7,8,0/a,8,9,1/aa,8,9,0");

    a = new NGramSynonymTokenizerTestAnalyzer(2, true, "a,aa/b,bb");
    stream = a.tokenStream("f", new StringReader("fedcabcdefa"));
    stream.reset();
    assertTokenStream(stream, "fe,0,2,1/ed,1,3,1/dc,2,4,1/c,3,4,0/a,4,5,1/aa,4,5,0/b,5,6,1/bb,5,6,0/c,6,7,1/cd,6,8,0/de,7,9,1/ef,8,10,1/f,9,10,0/a,10,11,1/aa,10,11,0");
  }

  @Test
  public void testComplexExpand3() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(3, true, "a,aa/b,bb");
    TokenStream stream = a.tokenStream("f", new StringReader("cabca"));
    stream.reset();
    assertTokenStream(stream, "c,0,1,1/a,1,2,1/aa,1,2,0/b,2,3,1/bb,2,3,0/c,3,4,1/a,4,5,1/aa,4,5,0");

    a = new NGramSynonymTokenizerTestAnalyzer(3, true, "a,aa/b,bb");
    stream = a.tokenStream("f", new StringReader("ccabcca"));
    stream.reset();
    assertTokenStream(stream, "cc,0,2,1/c,1,2,0/a,2,3,1/aa,2,3,0/b,3,4,1/bb,3,4,0/c,4,5,1/cc,4,6,0/c,5,6,0/a,6,7,1/aa,6,7,0");

    a = new NGramSynonymTokenizerTestAnalyzer(3, true, "a,aa/b,bb");
    stream = a.tokenStream("f", new StringReader("edcabcdea"));
    stream.reset();
    assertTokenStream(stream, "edc,0,3,1/dc,1,3,0/c,2,3,0/a,3,4,1/aa,3,4,0/b,4,5,1/bb,4,5,0/c,5,6,1/cd,5,7,0/cde,5,8,0/de,6,8,0/e,7,8,0/a,8,9,1/aa,8,9,0");

    a = new NGramSynonymTokenizerTestAnalyzer(3, true, "a,aa/b,bb");
    stream = a.tokenStream("f", new StringReader("fedcabcdefa"));
    stream.reset();
    assertTokenStream(stream, "fed,0,3,1/edc,1,4,1/dc,2,4,0/c,3,4,0/a,4,5,1/aa,4,5,0/b,5,6,1/bb,5,6,0/c,6,7,1/cd,6,8,0/cde,6,9,0/def,7,10,1/ef,8,10,0/f,9,10,0/a,10,11,1/aa,10,11,0");

    a = new NGramSynonymTokenizerTestAnalyzer(3, true, "a,aa/b,bb");
    stream = a.tokenStream("f", new StringReader("gfedcabcdefga"));
    stream.reset();
    assertTokenStream(stream, "gfe,0,3,1/fed,1,4,1/edc,2,5,1/dc,3,5,0/c,4,5,0/a,5,6,1/aa,5,6,0/b,6,7,1/bb,6,7,0/c,7,8,1/cd,7,9,0/cde,7,10,0/def,8,11,1/efg,9,12,1/fg,10,12,0/g,11,12,0/a,12,13,1/aa,12,13,0");
  }

  @Test
  public void testComplexExpand4() throws Exception {
    Analyzer a = new NGramSynonymTokenizerTestAnalyzer(4, true, "a,aa/b,bb");
    TokenStream stream = a.tokenStream("f", new StringReader("cabca"));
    stream.reset();
    assertTokenStream(stream, "c,0,1,1/a,1,2,1/aa,1,2,0/b,2,3,1/bb,2,3,0/c,3,4,1/a,4,5,1/aa,4,5,0");

    a = new NGramSynonymTokenizerTestAnalyzer(4, true, "a,aa/b,bb");
    stream = a.tokenStream("f", new StringReader("ccabcca"));
    stream.reset();
    assertTokenStream(stream, "cc,0,2,1/c,1,2,0/a,2,3,1/aa,2,3,0/b,3,4,1/bb,3,4,0/c,4,5,1/cc,4,6,0/c,5,6,0/a,6,7,1/aa,6,7,0");

    a = new NGramSynonymTokenizerTestAnalyzer(4, true, "a,aa/b,bb");
    stream = a.tokenStream("f", new StringReader("edcabcdea"));
    stream.reset();
    assertTokenStream(stream, "edc,0,3,1/dc,1,3,0/c,2,3,0/a,3,4,1/aa,3,4,0/b,4,5,1/bb,4,5,0/c,5,6,1/cd,5,7,0/cde,5,8,0/de,6,8,0/e,7,8,0/a,8,9,1/aa,8,9,0");

    a = new NGramSynonymTokenizerTestAnalyzer(4, true, "a,aa/b,bb");
    stream = a.tokenStream("f", new StringReader("fedcabcdefa"));
    stream.reset();
    assertTokenStream(stream, "fedc,0,4,1/edc,1,4,0/dc,2,4,0/c,3,4,0/a,4,5,1/aa,4,5,0/b,5,6,1/bb,5,6,0/c,6,7,1/cd,6,8,0/cde,6,9,0/cdef,6,10,0/def,7,10,0/ef,8,10,0/f,9,10,0/a,10,11,1/aa,10,11,0");

    a = new NGramSynonymTokenizerTestAnalyzer(4, true, "a,aa/b,bb");
    stream = a.tokenStream("f", new StringReader("gfedcabcdefga"));
    stream.reset();
    assertTokenStream(stream, "gfed,0,4,1/fedc,1,5,1/edc,2,5,0/dc,3,5,0/c,4,5,0/a,5,6,1/aa,5,6,0/b,6,7,1/bb,6,7,0/c,7,8,1/cd,7,9,0/cde,7,10,0/cdef,7,11,0/defg,8,12,1/efg,9,12,0/fg,10,12,0/g,11,12,0/a,12,13,1/aa,12,13,0");
  }

  private void assertTokenStream(TokenStream stream, String expectedStream) throws Exception {
    
    String[] expectedTokens = expectedStream.split("/");
    int count = 0;
    for(String expectedToken : expectedTokens){
      String[] attrs = expectedToken.split(",");
      assertTrue(stream.incrementToken());

      String term = attrs[0];
      assertAttribute(count, "term", term, stream.getAttribute(CharTermAttribute.class).toString());

      if(attrs.length > 1){
        int so = Integer.parseInt(attrs[1]);
        assertAttribute(count, "startOffset", so, stream.getAttribute(OffsetAttribute.class).startOffset());

        if(attrs.length > 2){
          int eo = Integer.parseInt(attrs[2]);
          assertAttribute(count, "endOffset", eo, stream.getAttribute(OffsetAttribute.class).endOffset());

          if(attrs.length > 3){
            int pi = Integer.parseInt(attrs[3]);
            assertAttribute(count, "posInc", pi, stream.getAttribute(PositionIncrementAttribute.class).getPositionIncrement());
          }
        }
      }
      count++;
    }
    assertFalse(stream.incrementToken());
  }
  
  private void assertAttribute(int count, String type, String expected, String actual) throws Exception {
    if(expected.equals("[null]"))
      assertNull(String.format("%s is invalid at token %d, expected : \"%s\" != actual : \"%s\"",
          type, count, expected, actual), actual);
    else
      assertEquals(String.format("%s is invalid at token %d, expected : \"%s\" != actual : \"%s\"",
          type, count, expected, actual), expected, actual);
  }
  
  private void assertAttribute(int count, String type, int expected, int actual) throws Exception {
    assertEquals(String.format("%s is invalid at token %d, expected : \"%d\" != actual : \"%d\"",
        type, count, expected, actual), expected, actual);
  }

  public static final class NGramSynonymTokenizerTestAnalyzer extends Analyzer {
    
    final int n;
    final String delimiters;
    final boolean expand;
    final SynonymMap synonyms;
    
    public NGramSynonymTokenizerTestAnalyzer(int n){
      this(n, NGramSynonymTokenizer.DEFAULT_DELIMITERS, false);
    }
    
    public NGramSynonymTokenizerTestAnalyzer(int n, boolean expand){
      this(n, NGramSynonymTokenizer.DEFAULT_DELIMITERS, expand);
    }
    
    public NGramSynonymTokenizerTestAnalyzer(int n, String delimiters, boolean expand){
      this(n, delimiters, expand, (String)null);
    }
    
    public NGramSynonymTokenizerTestAnalyzer(int n, boolean expand, String synonyms){
      this(n, NGramSynonymTokenizer.DEFAULT_DELIMITERS, expand, synonyms);
    }
    
    public NGramSynonymTokenizerTestAnalyzer(int n, String delimiters, boolean expand, String synonyms){
      this.n = n;
      this.delimiters = delimiters;
      this.expand = expand;
      this.synonyms = getSynonymMap(synonyms);
    }
    
    public NGramSynonymTokenizerTestAnalyzer(int n, String delimiters, boolean expand, SynonymMap synonyms){
      this.n = n;
      this.delimiters = delimiters;
      this.expand = expand;
      this.synonyms = synonyms;
    }

    protected TokenStreamComponents createComponents(String fieldName,
        Reader reader) {
      final Tokenizer source = new NGramSynonymTokenizer(reader, n, delimiters, expand, true, synonyms);
      return new TokenStreamComponents(source);
    }
    
    private SynonymMap getSynonymMap(String synonyms){
      if(synonyms != null){
        SolrSynonymParser parser = new SolrSynonymParser(true, true, NGramSynonymTokenizerFactory.getAnalyzer(true));
        try {
          parser.parse(new StringReader(synonyms.replace('/', '\n')));
          return parser.build();
        } catch (IOException e) {
          throw new RuntimeException();
        } catch (ParseException e) {
          throw new RuntimeException();
        }
      }
      else
        return null;
    }
  }
}
