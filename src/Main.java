// =====================================================================
// Assignment 1 - Myers' diff (Java)
// =====================================================================
//
// Program kaise chalate hain:
//   java Main lines     A B    -> Part A: file A se file B tak ka line diff
//   java Main highlight A B    -> Part B: wahi diff + har changed line pair ke liye "? old | new" line
//
// Output ka matlab:
//   " text"  -> line dono files mein same hai (keep)
//   "-text"  -> line sirf A mein thi, delete hui
//   "+text"  -> line sirf B mein hai, insert hui
//   "? 12-13 | 11-12" -> (sirf highlight mein) line ke andar kaun se characters badle
//
// Rule: ek change block mein pehle saari "-" lines, phir saari "+" lines.
// Agar koi file read nahi hui: stdout pe kuch nahi, stderr pe message, exit code 2.

import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;

public class Main {

    // =================================================================
    // PART 1: File ko lines mein todna
    // =================================================================
    //
    // Assignment ke rules:
    //   - file ko raw bytes ki tarah padho, text ki tarah nahi
    //   - sirf '\n' byte pe todo
    //   - agar last piece khaali hai to use hata do (last newline se extra line nahi banti)
    //   - '\r' line ka hissa hi rehta hai, isliye "a\r\n" aur "a\n" alag lines hain
    //
    // Har line ki copy banane ke bajaye hum poori file ek baar rakhte hain,
    // aur bas yaad rakhte hain ki har line kahan shuru aur kahan khatam hoti hai.

    static class FileLines {
        byte[] fileBytes;      // poori file ke bytes
        int[] lineStartAt;     // line i yahan se shuru hoti hai
        int[] lineEndAt;       // aur yahan khatam (ye position line mein shaamil nahi, '\n' bhi nahi)
        int totalLines;

        FileLines(byte[] fileBytes) {
            this.fileBytes = fileBytes;
            int lineCount = countLines(fileBytes);
            lineStartAt = new int[lineCount];
            lineEndAt = new int[lineCount];

            int currentLineStart = 0;
            for (int position = 0; position < fileBytes.length; position++) {
                if (fileBytes[position] == '\n') {
                    saveLine(currentLineStart, position);
                    currentLineStart = position + 1;    // agli line '\n' ke baad se shuru
                }
            }
            // last line ke baad '\n' na ho tab bhi wo ek line hai
            if (currentLineStart < fileBytes.length) {
                saveLine(currentLineStart, fileBytes.length);
            }
        }

        private void saveLine(int start, int end) {
            lineStartAt[totalLines] = start;
            lineEndAt[totalLines] = end;
            totalLines++;
        }

        // Har '\n' ek line, aur agar file '\n' pe khatam nahi hoti to ek line aur
        private static int countLines(byte[] bytes) {
            int count = 0;
            for (byte oneByte : bytes) {
                if (oneByte == '\n') count++;
            }
            boolean lastLineHasNoNewline = bytes.length > 0 && bytes[bytes.length - 1] != '\n';
            if (lastLineHasNoNewline) count++;
            return count;
        }

        int lengthOfLine(int line) {
            return lineEndAt[line] - lineStartAt[line];
        }

        // Line ko aisi String mein badlo jo tabhi equal ho jab bytes bilkul same hon.
        // ISO-8859-1 har byte ko exactly ek char banata hai, isliye invalid UTF-8 bhi theek chalta hai.
        String lineAsCompareKey(int line) {
            return new String(fileBytes, lineStartAt[line], lengthOfLine(line), StandardCharsets.ISO_8859_1);
        }

        // Part B ke liye: line ke characters (Unicode code points).
        // Java mein emoji 2 chars hota hai, lekin codePoints() use 1 hi ginta hai - yahi chahiye.
        int[] lineAsCharacters(int line) {
            String text = new String(fileBytes, lineStartAt[line], lengthOfLine(line), StandardCharsets.UTF_8);
            return text.codePoints().toArray();
        }
    }

    // Har alag line ko ek number de do. Same line (A mein ho ya B mein) ko same number milega.
    // Phir diff ko sirf ints compare karne padte hain - strings se bahut fast.
    // Example: A = [x, y, x], B = [y, z]  ->  A = [0, 1, 0], B = [1, 2]
    static int[] convertLinesToNumbers(FileLines file, HashMap<String, Integer> numberForLine) {
        int[] lineNumbers = new int[file.totalLines];
        for (int line = 0; line < file.totalLines; line++) {
            String key = file.lineAsCompareKey(line);
            Integer number = numberForLine.get(key);
            if (number == null) {
                number = numberForLine.size();     // nayi line hai, agla free number do
                numberForLine.put(key, number);
            }
            lineNumbers[line] = number;
        }
        return lineNumbers;
    }

    // =================================================================
    // PART 2: Myers' diff algorithm
    // =================================================================
    //
    // Ye kisi bhi do int sequences pe chalta hai: line numbers (Part A) ya characters (Part B).
    //
    // Edit graph (T2 notes wala):
    //   - x = sequence A mein position, y = sequence B mein position
    //   - right jaana    (x+1)      = A[x] delete karna
    //   - neeche jaana   (y+1)      = B[y] insert karna
    //   - diagonal jaana (x+1, y+1) = A[x] == B[y], free mein aage (isse "snake" kehte hain)
    //   - k = x - y  -> hum kaunse diagonal pe hain
    //   - V[k] = diagonal k pe ab tak sabse door kaunsa x pahuncha
    //   - d = ab tak kitne edits use kiye; d = 0, 1, 2, ... try karte hain jab tak end na mil jaye
    //
    // Simple version har d ke liye V ki copy save karta hai (baad mein path wapas dhoondhne ke liye).
    // 500k lines ki file pe ye copy bahut zyada memory khaati hai (768 MiB limit cross).
    //
    // Isliye hum linear-space version use karte hain (Myers paper, section 4b):
    //   1. start (0,0) se aage ki taraf search, aur end (N,M) se peeche ki taraf search - dono saath mein
    //   2. jahan dono search milti hain ("middle snake"), wo point kisi shortest path pe hota hai
    //   3. us point pe problem ko do hisson mein tod do: upar-baaye wala aur neeche-daaye wala
    //   4. dono hisson ko same tareeke se solve karo (recursion)
    // Memory sirf O(N + M), time abhi bhi O(N * D).
    //
    // Answer do arrays mein aata hai:
    //   isDeleted[i]  = true matlab A[i] delete hua
    //   isInserted[j] = true matlab B[j] insert hua
    // Jo mark nahi hua wo "keep" hai, aur A aur B ke kept parts order mein same hote hain.

    // Edit graph ka ek point (x, y)
    record Point(int x, int y) { }

    static class MyersDiff {
        private final int[] sequenceA;
        private final int[] sequenceB;
        final boolean[] isDeleted;
        final boolean[] isInserted;

        // Forward aur backward search ke V arrays.
        // k negative bhi ho sakta hai, isliye V[k] ko index (k + offset) pe rakhte hain.
        // Ek hi baar banate hain, har recursive call inhi ko reuse karti hai.
        private final int[] forwardV;
        private final int[] backwardV;
        private final int offset;

        MyersDiff(int[] sequenceA, int[] sequenceB) {
            this.sequenceA = sequenceA;
            this.sequenceB = sequenceB;
            isDeleted = new boolean[sequenceA.length];
            isInserted = new boolean[sequenceB.length];

            int maxRounds = (sequenceA.length + sequenceB.length + 1) / 2 + 1;
            offset = maxRounds + 1;
            forwardV = new int[2 * maxRounds + 3];
            backwardV = new int[2 * maxRounds + 3];
        }

        // Poore A aur B ka diff nikaalo. Result isDeleted / isInserted mein milega.
        MyersDiff findDiff() {
            diffPart(0, sequenceA.length, 0, sequenceB.length);
            return this;
        }

        // A[aStart..aEnd) aur B[bStart..bEnd) ka diff  (end wala index shaamil nahi)
        private void diffPart(int aStart, int aEnd, int bStart, int bEnd) {
            // Shuru ki same lines hamesha keep hoti hain - unhe skip karo
            while (aStart < aEnd && bStart < bEnd && sequenceA[aStart] == sequenceB[bStart]) {
                aStart++;
                bStart++;
            }
            // Aakhri ki same lines bhi hamesha keep hoti hain - unhe bhi skip karo
            while (aStart < aEnd && bStart < bEnd && sequenceA[aEnd - 1] == sequenceB[bEnd - 1]) {
                aEnd--;
                bEnd--;
            }

            // A mein kuch nahi bacha -> B ka bacha hua sab insert hai
            if (aStart == aEnd) {
                markAsInserted(bStart, bEnd);
                return;
            }
            // B mein kuch nahi bacha -> A ka bacha hua sab delete hai
            if (bStart == bEnd) {
                markAsDeleted(aStart, aEnd);
                return;
            }

            // Dono taraf kuch bacha hai, to kam se kam 2 edits lagenge.
            // Middle snake dhoondho aur wahan se problem ko do chhote hisson mein todo.
            Point meetingPoint = findMiddleSnake(aStart, aEnd, bStart, bEnd);
            int splitA = aStart + meetingPoint.x();
            int splitB = bStart + meetingPoint.y();
            diffPart(aStart, splitA, bStart, splitB);     // upar-baaya hissa
            diffPart(splitA, aEnd, splitB, bEnd);         // neeche-daaya hissa
        }

        private void markAsInserted(int from, int to) {
            for (int j = from; j < to; j++) isInserted[j] = true;
        }

        private void markAsDeleted(int from, int to) {
            for (int i = from; i < to; i++) isDeleted[i] = true;
        }

        // A[aStart..aEnd) vs B[bStart..bEnd) ke liye ek aisa point dhoondho jo shortest path pe ho.
        // Point (aStart, bStart) ke relative return hota hai.
        private Point findMiddleSnake(int aStart, int aEnd, int bStart, int bEnd) {
            int n = aEnd - aStart;              // A ke is hisse ki length
            int m = bEnd - bStart;              // B ke is hisse ki length
            int delta = n - m;                  // end point (n, m) ka diagonal
            boolean deltaIsOdd = (delta % 2) != 0;
            int maxRounds = (n + m + 1) / 2;    // har search ko zyada se zyada aadhe edits chahiye

            // Shuruaati value, taaki round d = 0 mein dono search diagonal 0 pe x = 0 se shuru hon
            forwardV[1 + offset] = 0;
            backwardV[1 + offset] = 0;

            for (int d = 0; d <= maxRounds; d++) {

                // ---------- Forward search: (0,0) se (n,m) ki taraf ----------
                for (int k = -d; k <= d; k += 2) {
                    int x = chooseStartX(forwardV, k, d);
                    int y = x - k;
                    x = followSnakeForward(x, y, n, m, aStart, bStart);   // jab tak same hai, diagonal chalo
                    y = x - k;
                    forwardV[k + offset] = x;

                    // delta odd ho to dono search sirf forward step ke baad mil sakti hain.
                    if (deltaIsOdd && backwardSearchReached(k, delta, d - 1, x, n)) {
                        return new Point(x, y);
                    }
                }

                // ---------- Backward search: (n,m) se (0,0) ki taraf ----------
                // Forward jaisa hi, bas A aur B ko peeche se padhte hain.
                // Yahan x aur y batate hain ki hum END se kitna door hain.
                for (int k = -d; k <= d; k += 2) {
                    int x = chooseStartX(backwardV, k, d);
                    int y = x - k;
                    x = followSnakeBackward(x, y, n, m, aEnd, bEnd);      // peeche ki taraf diagonal chalo
                    y = x - k;
                    backwardV[k + offset] = x;

                    // delta even ho to dono search sirf backward step ke baad mil sakti hain.
                    if (!deltaIsOdd && forwardSearchReached(k, delta, d, x, n)) {
                        // "end se doori" ko normal point mein badlo
                        return new Point(n - x, m - y);
                    }
                }
            }
            throw new IllegalStateException("middle snake nahi mila");   // aisa kabhi nahi hoga
        }

        // Myers ka main step: round d mein diagonal k pe kahan se shuru karein?
        //   - diagonal k+1 se NEECHE aao (insert, x same rehta hai), ya
        //   - diagonal k-1 se RIGHT aao  (delete, x ek badhta hai)
        // Jo bhi zyada aage pahuncha hai, wahi choose karo.
        private int chooseStartX(int[] v, int k, int d) {
            boolean comeDown = (k == -d) || (k != d && v[k - 1 + offset] < v[k + 1 + offset]);
            if (comeDown) {
                return v[k + 1 + offset];
            }
            return v[k - 1 + offset] + 1;
        }

        // SNAKE: jab tak A aur B ke elements same hain, diagonal pe free mein aage badho.
        // Naya x return karta hai.
        private int followSnakeForward(int x, int y, int n, int m, int aStart, int bStart) {
            while (x < n && y < m && sequenceA[aStart + x] == sequenceB[bStart + y]) {
                x++;
                y++;
            }
            return x;
        }

        // Backward wala SNAKE: same kaam, lekin A aur B ko end se padhte hain (upar-baayein chalna).
        private int followSnakeBackward(int x, int y, int n, int m, int aEnd, int bEnd) {
            while (x < n && y < m && sequenceA[aEnd - 1 - x] == sequenceB[bEnd - 1 - y]) {
                x++;
                y++;
            }
            return x;
        }

        // Forward diagonal k aur backward diagonal (delta - k) ek hi line hain.
        // Kya backward search is diagonal pe itna aa chuki hai ki dono overlap ho gayi?
        // backwardRoundsDone rounds ke baad backward ko diagonals -rounds..+rounds pata hote hain.
        private boolean backwardSearchReached(int k, int delta, int backwardRoundsDone, int forwardX, int n) {
            int backwardK = delta - k;
            boolean known = backwardK >= -backwardRoundsDone && backwardK <= backwardRoundsDone;
            return known && forwardX + backwardV[backwardK + offset] >= n;
        }

        // Upar wala hi check, ulta: kya forward search is diagonal pe backward se mil chuki hai?
        private boolean forwardSearchReached(int k, int delta, int forwardRoundsDone, int backwardX, int n) {
            int forwardK = delta - k;
            boolean known = forwardK >= -forwardRoundsDone && forwardK <= forwardRoundsDone;
            return known && backwardX + forwardV[forwardK + offset] >= n;
        }
    }

    // =================================================================
    // PART 3 (Part B): line ke andar kaun se characters badle
    // =================================================================

    // Purani aur nayi line ke characters pe wahi Myers diff chalao,
    // aur extra line banao, jaise "? 12-13 | 11-12".
    static String makeHighlightLine(int[] oldLineChars, int[] newLineChars) {
        MyersDiff charDiff = new MyersDiff(oldLineChars, newLineChars).findDiff();
        String oldRanges = marksToRangeText(charDiff.isDeleted);
        String newRanges = marksToRangeText(charDiff.isInserted);
        return "? " + oldRanges + " | " + newRanges;
    }

    // Marks ko ranges mein badlo. Range ka end shaamil nahi hota.
    //   [no, yes, yes, no, yes]  ->  "1-3,4-5"
    //   kuch bhi marked nahi     ->  "."
    // Touching ranges apne aap merge ho jaati hain, kyunki range sirf unmarked position pe khatam hoti hai.
    static String marksToRangeText(boolean[] isMarked) {
        StringBuilder rangeText = new StringBuilder();
        int position = 0;
        while (position < isMarked.length) {
            if (!isMarked[position]) {
                position++;
                continue;
            }
            int rangeStart = position;
            while (position < isMarked.length && isMarked[position]) position++;   // marked hisse ke end tak jao
            if (rangeText.length() > 0) rangeText.append(',');
            rangeText.append(rangeStart).append('-').append(position);
        }
        return rangeText.length() == 0 ? "." : rangeText.toString();
    }

    // =================================================================
    // PART 4: Diff print karna
    // =================================================================

    // Likho: prefix character + line ke exact bytes + '\n'
    static void printLine(OutputStream out, char prefix, FileLines file, int line) throws IOException {
        out.write(prefix);
        out.write(file.fileBytes, file.lineStartAt[line], file.lengthOfLine(line));
        out.write('\n');
    }

    static void printDiff(FileLines fileA, FileLines fileB, MyersDiff diff, boolean showHighlight,
                          OutputStream out) throws IOException {
        int linesInA = fileA.totalLines;
        int linesInB = fileB.totalLines;
        int nextLineA = 0;      // A ki agli line jo print karni hai
        int nextLineB = 0;      // B ki agli line jo print karni hai

        // Ek change block ki deleted aur inserted lines (ek baar banao, har block mein reuse)
        int[] deletedLines = new int[linesInA];
        int[] insertedLines = new int[linesInB];

        while (nextLineA < linesInA || nextLineB < linesInB) {

            // Keep line: dono taraf kuch mark nahi, matlab ye dono lines same hain
            boolean isKeepLine = nextLineA < linesInA && nextLineB < linesInB
                    && !diff.isDeleted[nextLineA] && !diff.isInserted[nextLineB];
            if (isKeepLine) {
                printLine(out, ' ', fileA, nextLineA);
                nextLineA++;
                nextLineB++;
                continue;
            }

            // Warna ye ek change block ki shuruaat hai.
            // Agli keep line aane tak saari deleted aur inserted lines jama karo.
            int deletedCount = 0;
            int insertedCount = 0;
            while ((nextLineA < linesInA && diff.isDeleted[nextLineA])
                    || (nextLineB < linesInB && diff.isInserted[nextLineB])) {
                while (nextLineA < linesInA && diff.isDeleted[nextLineA]) {
                    deletedLines[deletedCount++] = nextLineA++;
                }
                while (nextLineB < linesInB && diff.isInserted[nextLineB]) {
                    insertedLines[insertedCount++] = nextLineB++;
                }
            }

            // Delete-first rule: pehle saari "-" lines, phir saari "+" lines
            for (int i = 0; i < deletedCount; i++) {
                printLine(out, '-', fileA, deletedLines[i]);
            }
            for (int i = 0; i < insertedCount; i++) {
                printLine(out, '+', fileB, insertedLines[i]);

                // Part B: i-th "+" line ka jodi i-th "-" line hai (agar wo hai to)
                boolean hasPairLine = i < deletedCount;
                if (showHighlight && hasPairLine) {
                    printHighlightLine(out, fileA, deletedLines[i], fileB, insertedLines[i]);
                }
            }
        }
    }

    static void printHighlightLine(OutputStream out, FileLines fileA, int oldLine, FileLines fileB, int newLine)
            throws IOException {
        int[] oldChars = fileA.lineAsCharacters(oldLine);
        int[] newChars = fileB.lineAsCharacters(newLine);
        out.write(makeHighlightLine(oldChars, newChars).getBytes(StandardCharsets.US_ASCII));
        out.write('\n');
    }

    // =================================================================
    // PART 5: main
    // =================================================================

    public static void main(String[] args) throws IOException {
        // Saara output ek buffered stream se: har line pe System.out.println se bahut fast,
        // aur line ke bytes bilkul waise hi likhe jaate hain jaise file mein the
        OutputStream out = new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 16);
        int exitCode = run(args, out, System.err);
        out.flush();
        if (exitCode != 0) System.exit(exitCode);
    }

    // Poora kaam karta hai aur exit code return karta hai (0 = sab theek, 2 = galat arguments ya file nahi padhi).
    // main se alag rakha hai taaki tests isko seedha call kar sakein.
    static int run(String[] args, OutputStream out, PrintStream err) throws IOException {
        boolean isValidCommand = args.length == 3 && (args[0].equals("lines") || args[0].equals("highlight"));
        if (!isValidCommand) {
            err.println("usage: java Main lines|highlight A B");
            return 2;
        }
        boolean showHighlight = args[0].equals("highlight");

        // Kuch bhi print karne se pehle dono files padh lo, taaki galat file pe stdout khaali rahe
        FileLines fileA;
        FileLines fileB;
        try {
            fileA = new FileLines(Files.readAllBytes(Paths.get(args[1])));
            fileB = new FileLines(Files.readAllBytes(Paths.get(args[2])));
        } catch (IOException | RuntimeException e) {
            err.println("cannot read input file: " + e);
            return 2;
        }

        // Dono files ek hi map share karti hain, taaki same line ko A aur B mein same number mile
        HashMap<String, Integer> numberForLine = new HashMap<>();
        int[] numbersA = convertLinesToNumbers(fileA, numberForLine);
        int[] numbersB = convertLinesToNumbers(fileB, numberForLine);

        MyersDiff diff = new MyersDiff(numbersA, numbersB).findDiff();
        printDiff(fileA, fileB, diff, showHighlight, out);
        return 0;
    }
}
