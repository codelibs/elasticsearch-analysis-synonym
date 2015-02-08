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

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.store.ByteArrayDataInput;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.CharsRef;
import org.apache.lucene.util.UnicodeUtil;
import org.apache.lucene.util.fst.FST;

// https://issues.apache.org/jira/browse/LUCENE-5252
public final class NGramSynonymTokenizer extends Tokenizer {

    public static final int DEFAULT_N_SIZE = 2;

    public static final String DEFAULT_DELIMITERS = " 　\t\n\r";

    private final int n;

    private final String delimiters;

    private final boolean expand;

    private final boolean ignoreCase;

    private final SynonymMap synonymMap;

    private FST.Arc<BytesRef> scratchArc;

    private FST<BytesRef> fst;

    private FST.BytesReader fstReader;

    private BytesRef scratchBytes = new BytesRef();

    private CharsRef scratchChars = new CharsRef();

    private int longestMatchEndOffset;

    private int ch;

    static final int BUFFER_SIZE = 4096;

    private char[] readBuffer;

    private int readBufferIndex;

    private int readBufferLen;

    StringBuilder block;

    int blkStart, nextBlkStart, finalOffset;

    final PriorityQueue<MyToken> queue;

    MyToken prevToken;

    final List<MyToken> synonyms;

    private CharTermAttribute termAttr = addAttribute(CharTermAttribute.class);

    private OffsetAttribute offsetAttr = addAttribute(OffsetAttribute.class);

    private PositionIncrementAttribute posIncAttr = addAttribute(PositionIncrementAttribute.class);

    protected NGramSynonymTokenizer(Reader input, int n, String delimiters,
            boolean expand, boolean ignoreCase, SynonymMap map) {
        super(input);
        this.n = n;
        this.delimiters = delimiters;
        this.expand = expand;
        this.ignoreCase = ignoreCase;
        this.synonymMap = map;
        if (map != null) {
            this.fst = map.fst;
            if (fst == null) {
                throw new IllegalArgumentException("fst must be non-null");
            }
            this.fstReader = fst.getBytesReader();
            scratchArc = new FST.Arc<BytesRef>();
        }

        ch = 0;
        readBuffer = new char[BUFFER_SIZE];
        readBufferIndex = BUFFER_SIZE;
        readBufferLen = 0;
        block = new StringBuilder();
        nextBlkStart = 0;
        queue = new PriorityQueue<NGramSynonymTokenizer.MyToken>(100,
                new MyTokensComparator());
        this.synonyms = new ArrayList<NGramSynonymTokenizer.MyToken>();
    }

    public boolean incrementToken() throws IOException {
        while (true) {
            MyToken nextToken = getNextUniqueToken(queue, prevToken);
            if (nextToken == null) {
                getNextBlock();
                if (block.length() == 0)
                    return false;
                consultDictionary();
                tokenizeWholeBlock();
            } else {
                prevToken = nextToken;
                clearAttributes();
                termAttr.append(nextToken.word);
                finalOffset = correctOffset(blkStart + nextToken.endOffset);
                offsetAttr.setOffset(correctOffset(blkStart
                        + nextToken.startOffset), finalOffset);
                posIncAttr.setPositionIncrement(nextToken.posInc);
                return true;
            }
        }
    }

    static MyToken getNextUniqueToken(PriorityQueue<MyToken> que, MyToken prev) {
        while (true) {
            MyToken token = que.poll();
            if (token == null)
                return null;
            if (prev == null || !prev.identical(token)) {
                return token;
            }
        }
    }

    void consultDictionary() throws IOException {
        if (synonymMap == null)
            return;
        synonyms.clear();
        char[] key = block.toString().toCharArray();
        for (int start = 0; start < block.length();) {
            BytesRef matchOutput = getLongestMatchOutput(key, start);
            if (matchOutput == null) {
                start++;
                continue;
            }

            synonyms.add(new MyToken(key, start, longestMatchEndOffset, 1,
                    matchOutput.clone(), ignoreCase));
            start = longestMatchEndOffset;
        }
    }

    BytesRef getLongestMatchOutput(char[] src, int start) throws IOException {
        BytesRef pendingOutput = fst.outputs.getNoOutput();
        fst.getFirstArc(scratchArc);
        assert scratchArc.output == fst.outputs.getNoOutput();
        BytesRef matchOutput = null;

        int index = 0;
        while (start + index < src.length) {
            final int codePoint = Character.codePointAt(src, start + index,
                    src.length);
            if (fst.findTargetArc(ignoreCase ? Character.toLowerCase(codePoint)
                    : codePoint, scratchArc, scratchArc, fstReader) == null) {
                return matchOutput;
            }

            pendingOutput = fst.outputs.add(pendingOutput, scratchArc.output);

            if (scratchArc.isFinal()) {
                matchOutput = fst.outputs.add(pendingOutput,
                        scratchArc.nextFinalOutput);
                longestMatchEndOffset = start + index
                        + Character.charCount(codePoint);
            }

            index += Character.charCount(codePoint);
        }

        return matchOutput;
    }

    void tokenizeWholeBlock() {
        queue.clear();
        int nextStart = 0;
        int end = block.length();
        boolean afterSynonymProduced = false;
        final ByteArrayDataInput bytesReader = new ByteArrayDataInput();
        for (int idx = 0; idx < synonyms.size(); idx++) {
            MyToken synonym = synonyms.get(idx);
            tokenizePartialBlock(nextStart, synonym.startOffset,
                    afterSynonymProduced);

            // enqueue prev-synonym
            if (expand) {
                int limitOffset = 0;
                if (idx > 0)
                    limitOffset = synonyms.get(idx - 1).endOffset;
                processPrevSynonym(synonym.startOffset, limitOffset);
            }

            // enqueue synonyms
            if (expand) {
                bytesReader.reset(synonym.output.bytes, synonym.output.offset,
                        synonym.output.length);
                final int code = bytesReader.readVInt();
                final int count = code >>> 1;
                for (int i = 0; i < count; i++) {
                    synonymMap.words.get(bytesReader.readVInt(), scratchBytes);
                    if (scratchChars.chars.length < scratchBytes.length) {
                        scratchChars.chars = new char[scratchBytes.length];
                    }
                    scratchChars.length = UnicodeUtil.UTF8toUTF16(scratchBytes,
                            scratchChars.chars);
                    String word = scratchChars.toString();
                    int posInc = 0, seq = i + 1;
                    if (synonym.word.equals(word)) {
                        posInc = 1;
                        seq = 0;
                    }
                    queue.add(new MyToken(word, synonym.startOffset,
                            synonym.endOffset, posInc, seq));
                }
            } else {
                queue.add(synonym);
            }

            // enqueue after-synonym
            if (expand) {
                int limitOffset = block.length();
                if (idx < synonyms.size() - 1)
                    limitOffset = synonyms.get(idx + 1).startOffset;
                afterSynonymProduced = processAfterSynonym(synonym.endOffset,
                        limitOffset);
            }

            nextStart = synonym.endOffset;
        }
        tokenizePartialBlock(nextStart, end, afterSynonymProduced);
    }

    void tokenizePartialBlock(int startOffset, int endOffset,
            boolean afterSynonymProduced) {
        if (startOffset >= endOffset)
            return;

        int posInc = afterSynonymProduced ? 0 : 1;
        if (endOffset - startOffset < n) {
            queue.add(new MyToken(block.substring(startOffset, endOffset),
                    startOffset, endOffset, posInc));
            return;
        }

        for (int i = startOffset; i + n <= endOffset; i++) {
            queue.add(new MyToken(block.substring(i, i + n), i, i + n, posInc));
            posInc = 1;
        }
    }

    void processPrevSynonym(final int endOffset, final int limitOffset) {
        int startOffset = endOffset - 1;
        for (int len = 1; len < n && startOffset >= limitOffset; len++) {
            queue.add(new MyToken(block.substring(startOffset, endOffset),
                    startOffset, endOffset, 0));
            startOffset--;
        }
    }

    boolean processAfterSynonym(final int startOffset, final int limitOffset) {
        int qSize = queue.size();
        int endOffset = startOffset + 1;
        int posInc = 1;
        for (int len = 1; len < n && endOffset <= limitOffset; len++) {
            queue.add(new MyToken(block.substring(startOffset, endOffset),
                    startOffset, endOffset, posInc));
            endOffset++;
            posInc = 0;
        }
        return queue.size() > qSize;
    }

    @Override
    public void end() throws IOException {
        super.end();
        offsetAttr.setOffset(finalOffset, finalOffset);
    }

    @Override
    public void reset() throws IOException {
        super.reset();
        block.setLength(0);
        readBufferIndex = BUFFER_SIZE;
        readBufferLen = 0;
        ch = 0;
        blkStart = 0;
        nextBlkStart = 0;
    }

    boolean getNextBlock() throws IOException {
        blkStart = nextBlkStart;
        block.setLength(0);
        prevToken = null;
        while (true) {
            if (ch != -1)
                ch = readCharFromBuffer();
            if (ch == -1)
                break;
            else if (!isDelimiter(ch))
                block.append((char) ch);
            else if (block.length() > 0)
                break;
            else
                blkStart++;
        }
        if (block.length() == 0)
            return false;
        return true;
    }

    int readCharFromBuffer() throws IOException {
        if (readBufferIndex >= readBufferLen) {
            readBufferLen = input.read(readBuffer);
            if (readBufferLen == -1) {
                return -1;
            }
            readBufferIndex = 0;
        }
        int c = (int) readBuffer[readBufferIndex++];
        nextBlkStart++;
        return c;
    }

    boolean isDelimiter(int c) {
        return delimiters.indexOf(c) >= 0;
    }

    static class MyToken {
        final String word;

        final int startOffset, endOffset, posInc, seq;

        final BytesRef output;

        public MyToken(char[] key, int startOffset, int endOffset, int posInc,
                BytesRef output, boolean ignoreCase) {
            this.word = ignoreCase ? new String(key, startOffset, endOffset
                    - startOffset).toLowerCase() : new String(key, startOffset,
                    endOffset - startOffset);
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.posInc = posInc;
            this.output = output;
            this.seq = 0; // zero for seq means that this token is the original of synonyms
        }

        public MyToken(String word, int startOffset, int endOffset, int posInc) {
            this(word, startOffset, endOffset, posInc, Integer.MAX_VALUE); // Integer.MAX_VALUE for seq means unused
        }

        public MyToken(String word, int startOffset, int endOffset, int posInc,
                int seq) {
            this.word = word;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.posInc = posInc;
            this.output = null; // means unused
            this.seq = seq;
        }

        public boolean identical(MyToken o) {
            if (o.posInc != 0)
                return false;
            if (!word.equals(o.word))
                return false;
            if (startOffset != o.startOffset)
                return false;
            if (endOffset != o.endOffset)
                return false;
            return true;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(word).append(',').append(startOffset).append(',')
                    .append(endOffset).append(',').append(posInc);
            return sb.toString();
        }

        @Override
        public boolean equals(Object other) {
            if (other == null || !(other instanceof MyToken))
                return false;
            MyToken o = (MyToken) other;
            if (!word.equals(o.word))
                return false;
            if (startOffset != o.startOffset)
                return false;
            if (endOffset != o.endOffset)
                return false;
            if (posInc != o.posInc)
                return false;
            return true;
        }

        @Override
        public int hashCode() {
            return word.hashCode() + posInc << 30 + startOffset << 15 + endOffset;
        }
    }

    /*  
      static class SynInfo {
        final String src;
        final int offset, length;
        final String[] synonyms;
        Mode mode;
        int count;
        SynInfo(String src, int offset, int length, String[] synonyms){
          this.src = src;
          this.offset = offset;
          this.length = length;
          this.synonyms = synonyms;
        }
        
        static enum Mode {
          PREV, SYN, AFTER;
        }
      }
      */

    static class MyTokensComparator implements Comparator<MyToken> {
        public int compare(MyToken t1, MyToken t2) {
            if (t1.startOffset < t2.startOffset)
                return -1;
            else if (t1.startOffset > t2.startOffset)
                return 1;

            if (t1.endOffset < t2.endOffset)
                return -1;
            else if (t1.endOffset > t2.endOffset)
                return 1;

            if (t1.posInc > t2.posInc)
                return -1;
            else if (t1.posInc < t2.posInc)
                return 1;

            if (t1.seq < t2.seq)
                return -1;
            else if (t1.seq > t2.seq)
                return 1;

            return -1;
        }
    }
}
