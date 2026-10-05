// Assignment 1 - Myers' diff (Java)
//
// How to run:
//   java Main lines     A B    -> Part A: minimal line diff from file A to file B
//   java Main highlight A B    -> Part B: same diff, plus a "? old | new" line for each changed pair
//
// Output of "lines": one line per step of the edit script
//   " text"  keep   (line is in both files)
//   "-text"  delete (line is only in A)
//   "+text"  insert (line is only in B)
// Inside every change block, all "-" lines are printed before any "+" line.
//
// Output of "highlight": the same, and after each paired "+" line one extra line
//   "? <changed ranges in old line> | <changed ranges in new line>"   e.g.  "? 12-13 | 11-12"
//
// If a file cannot be read: nothing on stdout, a message on stderr, exit code 2.

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
    // 1. Reading a file into lines
    // =================================================================
    //
    // Rules from the assignment:
    //   - read raw bytes, not text
    //   - split on the byte '\n'
    //   - if the last piece is empty, drop it (so a final newline adds no extra line)
    //   - keep '\r' as part of the line ("a\r\n" and "a\n" are different lines)
    //
    // We do not copy each line. We keep the whole file and remember
    // where every line starts and ends inside it.

    static class FileLines {
        byte[] content;      // the whole file
        int[] lineStart;     // line i starts at content[lineStart[i]]
        int[] lineEnd;       // and ends just before content[lineEnd[i]] (the '\n' is not included)
        int lineCount;

        FileLines(byte[] content) {
            this.content = content;
            int total = countLines(content);
            lineStart = new int[total];
            lineEnd = new int[total];

            int startOfCurrentLine = 0;
            for (int pos = 0; pos < content.length; pos++) {
                if (content[pos] == '\n') {
                    addLine(startOfCurrentLine, pos);
                    startOfCurrentLine = pos + 1;
                }
            }
            // the last line may have no '\n' after it
            if (startOfCurrentLine < content.length) {
                addLine(startOfCurrentLine, content.length);
            }
        }

        private void addLine(int start, int end) {
            lineStart[lineCount] = start;
            lineEnd[lineCount] = end;
            lineCount++;
        }

        // one line per '\n', plus one more if the file does not end with '\n'
        private static int countLines(byte[] content) {
            int count = 0;
            for (byte value : content) {
                if (value == '\n') count++;
            }
            boolean lastLineHasNoNewline = content.length > 0 && content[content.length - 1] != '\n';
            if (lastLineHasNoNewline) count++;
            return count;
        }

        int lineLength(int line) {
            return lineEnd[line] - lineStart[line];
        }

        // Line as a String that is equal to another only if the bytes are equal.
        // ISO-8859-1 turns every byte into exactly one char, so this also works
        // for bytes that are not valid UTF-8.
        String lineAsKey(int line) {
            return new String(content, lineStart[line], lineLength(line), StandardCharsets.ISO_8859_1);
        }

        // Line as Unicode code points, for Part B (highlight files are always valid UTF-8).
        // An emoji is one code point, even though Java stores it as two chars.
        int[] lineAsCodePoints(int line) {
            String text = new String(content, lineStart[line], lineLength(line), StandardCharsets.UTF_8);
            return text.codePoints().toArray();
        }
    }

    // Gives every different line a number. Equal lines (in either file) get the same number.
    // Then the diff only has to compare ints, which is much faster than comparing strings.
    static int[] linesToNumbers(FileLines file, HashMap<String, Integer> numberOfLine) {
        int[] numbers = new int[file.lineCount];
        for (int line = 0; line < file.lineCount; line++) {
            String key = file.lineAsKey(line);
            Integer number = numberOfLine.get(key);
            if (number == null) {
                number = numberOfLine.size();      // next unused number
                numberOfLine.put(key, number);
            }
            numbers[line] = number;
        }
        return numbers;
    }

    // =================================================================
    // 2. Myers' diff algorithm
    // =================================================================
    //
    // It works on any two int sequences: line numbers (Part A) or code points (Part B).
    //
    // The edit graph (from T2):
    //   - x is a position in sequence A, y is a position in sequence B
    //   - move right   (x+1)       = delete A[x]
    //   - move down    (y+1)       = insert B[y]
    //   - move diagonal (x+1, y+1) = A[x] equals B[y], keep it, costs nothing (a "snake")
    //   - k = x - y is the number of the diagonal we are on
    //   - V[k] = the furthest x we have reached on diagonal k
    //   - d = number of edits used so far; we try d = 0, 1, 2, ... until we reach the end
    //
    // The simple version saves a copy of V for every d so it can walk back
    // and find the path. For big files that copy uses far too much memory.
    //
    // So we use the linear-space version (Myers' paper, section 4b):
    //   - search forward from the start (0, 0) and backward from the end (N, M) at the same time
    //   - the place where the two searches meet (the "middle snake") lies on a shortest path
    //   - split the problem there into a top-left part and a bottom-right part
    //   - solve both parts the same way (recursion)
    // Memory is now only O(N + M), and time is still O(N * D).
    //
    // The answer is stored as two arrays of marks:
    //   isDeleted[i]  = true when A[i] is deleted
    //   isInserted[j] = true when B[j] is inserted
    // Everything not marked is kept, and the kept parts of A and B are equal, in order.

    // A point (x, y) in the edit graph
    record Point(int x, int y) { }

    static class MyersDiff {
        private final int[] a;
        private final int[] b;
        final boolean[] isDeleted;
        final boolean[] isInserted;

        // V arrays for the forward and the backward search.
        // k can be negative, so V[k] is stored at index k + offset.
        // They are made once and reused by every recursive call.
        private final int[] forwardV;
        private final int[] backwardV;
        private final int offset;

        MyersDiff(int[] a, int[] b) {
            this.a = a;
            this.b = b;
            isDeleted = new boolean[a.length];
            isInserted = new boolean[b.length];

            int maxRounds = (a.length + b.length + 1) / 2 + 1;
            offset = maxRounds + 1;
            forwardV = new int[2 * maxRounds + 3];
            backwardV = new int[2 * maxRounds + 3];
        }

        // Runs the diff on the whole of a and b. Results are in isDeleted / isInserted.
        MyersDiff compute() {
            diffRange(0, a.length, 0, b.length);
            return this;
        }

        // Diff of a[aStart..aEnd) against b[bStart..bEnd)  (end not included)
        private void diffRange(int aStart, int aEnd, int bStart, int bEnd) {
            // Equal elements at the start are always kept: skip them
            while (aStart < aEnd && bStart < bEnd && a[aStart] == b[bStart]) {
                aStart++;
                bStart++;
            }
            // Equal elements at the end are always kept: skip them
            while (aStart < aEnd && bStart < bEnd && a[aEnd - 1] == b[bEnd - 1]) {
                aEnd--;
                bEnd--;
            }

            // Nothing left in A: the rest of B is inserted
            if (aStart == aEnd) {
                for (int j = bStart; j < bEnd; j++) isInserted[j] = true;
                return;
            }
            // Nothing left in B: the rest of A is deleted
            if (bStart == bEnd) {
                for (int i = aStart; i < aEnd; i++) isDeleted[i] = true;
                return;
            }

            // Both sides still have elements and need at least 2 edits here,
            // so the split point is strictly inside and both halves are smaller.
            Point split = findMiddleSnake(aStart, aEnd, bStart, bEnd);
            int splitA = aStart + split.x();
            int splitB = bStart + split.y();
            diffRange(aStart, splitA, bStart, splitB);     // top-left part
            diffRange(splitA, aEnd, splitB, bEnd);         // bottom-right part
        }

        // Finds a point on a shortest edit path for a[aStart..aEnd) vs b[bStart..bEnd).
        // The point is returned relative to (aStart, bStart).
        private Point findMiddleSnake(int aStart, int aEnd, int bStart, int bEnd) {
            int n = aEnd - aStart;              // length of this part of a
            int m = bEnd - bStart;              // length of this part of b
            int delta = n - m;                  // the diagonal of the end point (n, m)
            boolean deltaIsOdd = (delta % 2) != 0;
            int maxRounds = (n + m + 1) / 2;    // each search needs at most half of the edits

            // Start values, so that in round d = 0 both searches begin at x = 0 on diagonal 0
            forwardV[1 + offset] = 0;
            backwardV[1 + offset] = 0;

            for (int d = 0; d <= maxRounds; d++) {

                // ---------- forward search, from (0, 0) towards (n, m) ----------
                for (int k = -d; k <= d; k += 2) {
                    int x = nextStartX(forwardV, k, d);
                    int y = x - k;

                    // Follow the snake: keep moving diagonally while the elements are equal
                    while (x < n && y < m && a[aStart + x] == b[bStart + y]) {
                        x++;
                        y++;
                    }
                    forwardV[k + offset] = x;

                    // If delta is odd, the two searches can only meet right after a forward step.
                    // Forward diagonal k is the same line as backward diagonal (delta - k).
                    // The backward search has finished d - 1 rounds, so it knows diagonals -(d-1)..(d-1).
                    int backwardK = delta - k;
                    boolean backwardKnowsThisDiagonal = backwardK >= -(d - 1) && backwardK <= d - 1;
                    if (deltaIsOdd && backwardKnowsThisDiagonal
                            && x + backwardV[backwardK + offset] >= n) {
                        return new Point(x, y);
                    }
                }

                // ---------- backward search, from (n, m) towards (0, 0) ----------
                // Same steps as forward, but we read a and b from the end.
                // Here x and y count how far we are from the END of a and b.
                for (int k = -d; k <= d; k += 2) {
                    int x = nextStartX(backwardV, k, d);
                    int y = x - k;

                    // Follow the snake backwards (moving up-left)
                    while (x < n && y < m && a[aEnd - 1 - x] == b[bEnd - 1 - y]) {
                        x++;
                        y++;
                    }
                    backwardV[k + offset] = x;

                    // If delta is even, the two searches can only meet right after a backward step.
                    // The forward search has finished d rounds, so it knows diagonals -d..d.
                    int forwardK = delta - k;
                    boolean forwardKnowsThisDiagonal = forwardK >= -d && forwardK <= d;
                    if (!deltaIsOdd && forwardKnowsThisDiagonal
                            && x + forwardV[forwardK + offset] >= n) {
                        // turn "distance from the end" back into a normal point
                        return new Point(n - x, m - y);
                    }
                }
            }
            throw new IllegalStateException("middle snake not found");   // cannot happen
        }

        // The usual Myers step: where do we start on diagonal k in round d?
        //   - come DOWN from diagonal k+1 (an insert, x stays the same), or
        //   - come RIGHT from diagonal k-1 (a delete, x goes up by 1),
        // and pick whichever of the two has gone further.
        private int nextStartX(int[] v, int k, int d) {
            boolean goDown = (k == -d) || (k != d && v[k - 1 + offset] < v[k + 1 + offset]);
            if (goDown) {
                return v[k + 1 + offset];
            }
            return v[k - 1 + offset] + 1;
        }
    }

    // =================================================================
    // 3. Part B: which characters changed inside a line pair
    // =================================================================

    // Runs the same Myers diff on the characters of the old and new line and
    // returns the extra line, for example "? 12-13 | 11-12".
    static String buildHighlightLine(int[] oldChars, int[] newChars) {
        MyersDiff charDiff = new MyersDiff(oldChars, newChars).compute();
        return "? " + marksToRanges(charDiff.isDeleted) + " | " + marksToRanges(charDiff.isInserted);
    }

    // Turns marks into ranges, for example
    //   [no, yes, yes, no, yes]  ->  "1-3,4-5"     (the end of a range is not included)
    //   nothing marked           ->  "."
    // Touching ranges are merged automatically, because a range only ends at an unmarked position.
    static String marksToRanges(boolean[] marked) {
        StringBuilder ranges = new StringBuilder();
        int pos = 0;
        while (pos < marked.length) {
            if (!marked[pos]) {
                pos++;
                continue;
            }
            int rangeStart = pos;
            while (pos < marked.length && marked[pos]) pos++;
            if (ranges.length() > 0) ranges.append(',');
            ranges.append(rangeStart).append('-').append(pos);
        }
        return ranges.length() == 0 ? "." : ranges.toString();
    }

    // =================================================================
    // 4. Printing the diff
    // =================================================================

    // Writes: prefix character + the exact bytes of the line + '\n'
    static void writeLine(OutputStream out, char prefix, FileLines file, int line) throws IOException {
        out.write(prefix);
        out.write(file.content, file.lineStart[line], file.lineLength(line));
        out.write('\n');
    }

    static void printDiff(FileLines fileA, FileLines fileB, MyersDiff diff, boolean showHighlights,
                          OutputStream out) throws IOException {
        int countA = fileA.lineCount;
        int countB = fileB.lineCount;
        int lineA = 0;      // next line of A to print
        int lineB = 0;      // next line of B to print

        // line numbers of the current change block (made once, reused for every block)
        int[] deletedLines = new int[countA];
        int[] insertedLines = new int[countB];

        while (lineA < countA || lineB < countB) {

            // Keep line: neither side is marked, so these two lines are the same
            boolean bothKept = lineA < countA && lineB < countB
                    && !diff.isDeleted[lineA] && !diff.isInserted[lineB];
            if (bothKept) {
                writeLine(out, ' ', fileA, lineA);
                lineA++;
                lineB++;
                continue;
            }

            // Otherwise we are at the start of a change block.
            // Collect every deleted and inserted line until the next kept line.
            int deletedCount = 0;
            int insertedCount = 0;
            while ((lineA < countA && diff.isDeleted[lineA]) || (lineB < countB && diff.isInserted[lineB])) {
                while (lineA < countA && diff.isDeleted[lineA]) deletedLines[deletedCount++] = lineA++;
                while (lineB < countB && diff.isInserted[lineB]) insertedLines[insertedCount++] = lineB++;
            }

            // Delete-first rule: all "-" lines, then all "+" lines
            for (int i = 0; i < deletedCount; i++) {
                writeLine(out, '-', fileA, deletedLines[i]);
            }
            for (int i = 0; i < insertedCount; i++) {
                writeLine(out, '+', fileB, insertedLines[i]);

                // Part B: the i-th "+" line is paired with the i-th "-" line (if there is one)
                boolean hasPair = i < deletedCount;
                if (showHighlights && hasPair) {
                    int[] oldChars = fileA.lineAsCodePoints(deletedLines[i]);
                    int[] newChars = fileB.lineAsCodePoints(insertedLines[i]);
                    out.write(buildHighlightLine(oldChars, newChars).getBytes(StandardCharsets.US_ASCII));
                    out.write('\n');
                }
            }
        }
    }

    // =================================================================
    // 5. main
    // =================================================================

    public static void main(String[] args) throws IOException {
        // one buffered stream for all output: much faster than System.out.println per line,
        // and it writes the line bytes exactly as they are
        OutputStream out = new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 16);
        int exitCode = run(args, out, System.err);
        out.flush();
        if (exitCode != 0) System.exit(exitCode);
    }

    // Does the whole job and returns the exit code (0 = ok, 2 = bad arguments or unreadable file).
    // It is separate from main so that the tests can call it directly.
    static int run(String[] args, OutputStream out, PrintStream err) throws IOException {
        boolean validCommand = args.length == 3 && (args[0].equals("lines") || args[0].equals("highlight"));
        if (!validCommand) {
            err.println("usage: java Main lines|highlight A B");
            return 2;
        }
        boolean showHighlights = args[0].equals("highlight");

        // Read both files before printing anything, so a bad file leaves stdout empty
        FileLines fileA;
        FileLines fileB;
        try {
            fileA = new FileLines(Files.readAllBytes(Paths.get(args[1])));
            fileB = new FileLines(Files.readAllBytes(Paths.get(args[2])));
        } catch (IOException | RuntimeException e) {
            err.println("cannot read input file: " + e);
            return 2;
        }

        // Both files share one map, so the same line gets the same number in A and in B
        HashMap<String, Integer> numberOfLine = new HashMap<>();
        int[] numbersA = linesToNumbers(fileA, numberOfLine);
        int[] numbersB = linesToNumbers(fileB, numberOfLine);

        MyersDiff diff = new MyersDiff(numbersA, numbersB).compute();
        printDiff(fileA, fileB, diff, showHighlights, out);
        return 0;
    }
}
