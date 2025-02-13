/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.index.internal.gbptree;

import static java.lang.String.format;
import static org.neo4j.index.internal.gbptree.GenerationSafePointer.EMPTY_GENERATION;
import static org.neo4j.index.internal.gbptree.GenerationSafePointer.MIN_GENERATION;
import static org.neo4j.index.internal.gbptree.GenerationSafePointer.checksumOf;
import static org.neo4j.index.internal.gbptree.GenerationSafePointer.readChecksum;
import static org.neo4j.index.internal.gbptree.GenerationSafePointer.readGeneration;
import static org.neo4j.index.internal.gbptree.GenerationSafePointer.readPointer;

import org.neo4j.io.pagecache.PageCursor;

/**
 * Two {@link GenerationSafePointer} forming the basis for a B+tree becoming generate-aware.
 * <p>
 * Generally a GSP fall into one out of these categories:
 * <ul>
 * <li>STABLE: generation made durable and safe by a checkpoint</li>
 * <li>UNSTABLE: generation which is currently under evolution and isn't safe until next checkpoint</li>
 * <li>EMPTY: have never been written</li>
 * </ul>
 * There are variations of pointers written in UNSTABLE generation:
 * <ul>
 * <li>BROKEN: written during a concurrent page cache flush and wasn't flushed after that point before crash</li>
 * <li>CRASH: pointers written as UNSTABLE before a crash happened (non-clean shutdown) are seen as
 * CRASH during recovery</li>
 * </ul>
 * <p>
 * Combinations of above mentioned states of the two pointers dictates which, if any, to read from or write to.
 * From the perspective of callers there's only "read" and "write", the two pointers are hidden.
 * <p>
 * All methods are static and all interaction is made with primitives.
 * <p>
 * Flags in results from read/write method calls. Pointer is 6B so msb 2B can be used for flags,
 * although the most common case (successful read) has its flag zeros so a successful read doesn't need
 * any masking to extract pointer.
 * <pre>
 *     WRITE
 * [_1__,____][___ ,    ][ ... 6B pointer data ... ]
 *  ▲ ▲▲ ▲▲▲▲  ▲▲▲
 *  │ ││ ││││  │││
 *  │ ││ ││││  └└└────────────────────────────────────── POINTER STATE B (on failure)
 *  │ ││ │└└└─────────────────────────────────────────── POINTER STATE A (on failure)
 *  │ │└─└────────────────────────────────────────────── GENERATION COMPARISON (on failure):{@link #FLAG_GENERATION_B_BIG},
 *  │ │                                                  {@link #FLAG_GENERATION_EQUAL}, {@link #FLAG_GENERATION_A_BIG}
 *  │ └───────────────────────────────────────────────── 0:{@link #FLAG_SLOT_A}/1:{@link #FLAG_SLOT_B} (on success)
 *  └─────────────────────────────────────────────────── 0:{@link #FLAG_SUCCESS}/1:{@link #FLAG_FAIL}
 * </pre>
 * <pre>
 *     READ failure
 * [10__,____][__  ,    ][ ... 6B pointer data ... ]
 *    ▲▲ ▲▲▲▲  ▲▲
 *    ││ ││││  ││
 *    ││ │││└──└└─────────────────────────────────────── POINTER STATE B
 *    ││ └└└──────────────────────────────────────────── POINTER STATE A
 *    └└──────────────────────────────────────────────── GENERATION COMPARISON:
 *                                                       {@link #FLAG_GENERATION_B_BIG}, {@link #FLAG_GENERATION_EQUAL},
 *                                                       {@link #FLAG_GENERATION_A_BIG}
 * </pre>
 * <pre>
 *     READ success
 * [00_ ,    ][    ,    ][ ... 6B pointer data ... ]
 *    ▲
 *    └───────────────────────────────────────────────── 0:{@link #FLAG_SLOT_A}/1:{@link #FLAG_SLOT_B}
 * </pre>
 */
class GenerationSafePointerPair {
    static final int SIZE = GenerationSafePointer.SIZE * 2;
    static final String GENERATION_COMPARISON_NAME_B_BIG = "A < B";
    static final String GENERATION_COMPARISON_NAME_A_BIG = "A > B";
    static final String GENERATION_COMPARISON_NAME_EQUAL = "A == B";

    // Pointer states
    static final byte STABLE = 0; // any previous generation made safe by a checkpoint
    static final byte UNSTABLE = 1; // current generation, generation under evolution until next checkpoint
    static final byte CRASH = 2; // pointer written as unstable and didn't make it to checkpoint before crashing
    static final byte BROKEN = 3; // mismatching checksum
    static final byte EMPTY = 4; // generation and pointer all zeros

    // Flags and failure information
    static final long FLAG_SUCCESS = 0x00000000_00000000L;
    static final long FLAG_FAIL = 0x80000000_00000000L;
    static final long FLAG_READ = 0x00000000_00000000L;
    static final long FLAG_WRITE = 0x40000000_00000000L;
    static final long FLAG_GENERATION_EQUAL = 0x00000000_00000000L;
    static final long FLAG_GENERATION_A_BIG = 0x08000000_00000000L;
    static final long FLAG_GENERATION_B_BIG = 0x10000000_00000000L;
    static final long FLAG_SLOT_A = 0x00000000_00000000L;
    static final long FLAG_SLOT_B = 0x20000000_00000000L;
    static final int SHIFT_STATE_A = 56;
    static final int SHIFT_STATE_B = 53;

    // Aggregations
    static final long SUCCESS_WRITE_TO_B = FLAG_SUCCESS | FLAG_WRITE | FLAG_SLOT_B;
    static final long SUCCESS_WRITE_TO_A = FLAG_SUCCESS | FLAG_WRITE | FLAG_SLOT_A;

    // Masks
    static final long SUCCESS_MASK = FLAG_SUCCESS | FLAG_FAIL;
    static final long READ_OR_WRITE_MASK = FLAG_READ | FLAG_WRITE;
    static final long SLOT_MASK = FLAG_SLOT_A | FLAG_SLOT_B;
    static final long STATE_MASK = 0x7; // After shift
    static final long GENERATION_COMPARISON_MASK =
            FLAG_GENERATION_EQUAL | FLAG_GENERATION_A_BIG | FLAG_GENERATION_B_BIG;
    static final long POINTER_MASK = 0x0000FFFF_FFFFFFFFL;

    private GenerationSafePointerPair() {}

    /**
     * Reads a GSPP, returning the read pointer or a failure. Check success/failure using {@link #isSuccess(long)}
     * and if failure extract more information using {@link #failureDescription(long, long, String, long, long, String, String, int)}.
     *
     * @param cursor {@link PageCursor} to read from, placed at the beginning of the GSPP.
     * @param stableGeneration stable index generation.
     * @param unstableGeneration unstable index generation.
     * @param generationTarget target to write the generation of the selected pointer.
     * @return most recent readable pointer, or failure. Check result using {@link #isSuccess(long)}.
     */
    public static long read(
            PageCursor cursor,
            long stableGeneration,
            long unstableGeneration,
            GBPTreeGenerationTarget generationTarget) {
        // Try A
        long generationA = readGeneration(cursor);
        long pointerA = readPointer(cursor);
        short readChecksumA = readChecksum(cursor);
        short checksumA = checksumOf(generationA, pointerA);
        boolean correctChecksumA = readChecksumA == checksumA;

        // Try B
        long generationB = readGeneration(cursor);
        long pointerB = readPointer(cursor);
        short readChecksumB = readChecksum(cursor);
        short checksumB = checksumOf(generationB, pointerB);
        boolean correctChecksumB = readChecksumB == checksumB;

        byte pointerStateA =
                pointerState(stableGeneration, unstableGeneration, generationA, pointerA, correctChecksumA);
        byte pointerStateB =
                pointerState(stableGeneration, unstableGeneration, generationB, pointerB, correctChecksumB);

        if (pointerStateA == UNSTABLE) {
            if (pointerStateB == STABLE || pointerStateB == EMPTY) {
                return buildSuccessfulReadResult(FLAG_SLOT_A, generationA, pointerA, generationTarget);
            }
        } else if (pointerStateB == UNSTABLE) {
            if (pointerStateA == STABLE || pointerStateA == EMPTY) {
                return buildSuccessfulReadResult(FLAG_SLOT_B, generationB, pointerB, generationTarget);
            }
        } else if (pointerStateA == STABLE && pointerStateB == STABLE) {
            // compare generation
            if (generationA > generationB) {
                return buildSuccessfulReadResult(FLAG_SLOT_A, generationA, pointerA, generationTarget);
            } else if (generationB > generationA) {
                return buildSuccessfulReadResult(FLAG_SLOT_B, generationB, pointerB, generationTarget);
            }
        } else if (pointerStateA == STABLE) {
            return buildSuccessfulReadResult(FLAG_SLOT_A, generationA, pointerA, generationTarget);
        } else if (pointerStateB == STABLE) {
            return buildSuccessfulReadResult(FLAG_SLOT_B, generationB, pointerB, generationTarget);
        }

        generationTarget.accept(EMPTY_GENERATION);
        return FLAG_FAIL
                | FLAG_READ
                | generationState(generationA, generationB)
                | ((long) pointerStateA) << SHIFT_STATE_A
                | ((long) pointerStateB) << SHIFT_STATE_B;
    }

    private static long buildSuccessfulReadResult(
            long slot, long generation, long pointer, GBPTreeGenerationTarget generationTarget) {
        generationTarget.accept(generation);
        return FLAG_SUCCESS | FLAG_READ | slot | pointer;
    }

    /**
     * Writes a GSP at one of the GSPP slots A/B, returning the result.
     * Check success/failure using {@link #isSuccess(long)} and if failure extract more information using
     * {@link #failureDescription(long, long, String, long, long, String, String, int)}.
     *
     * @param cursor {@link PageCursor} to write to, placed at the beginning of the GSPP.
     * @param pointer pageId to write.
     * @param stableGeneration stable index generation.
     * @param unstableGeneration unstable index generation, which will be the generation to write in the slot.
     * @return {@code true} on success, otherwise {@code false} on failure.
     */
    public static long write(PageCursor cursor, long pointer, long stableGeneration, long unstableGeneration) {
        // Later there will be a selection which "slot" of GSP out of the two to write into.
        int offset = cursor.getOffset();
        pointer = pointer(pointer);

        // Try A
        long generationA = readGeneration(cursor);
        long pointerA = readPointer(cursor);
        short readChecksumA = readChecksum(cursor);
        short checksumA = checksumOf(generationA, pointerA);
        boolean correctChecksumA = readChecksumA == checksumA;

        // Try B
        long generationB = readGeneration(cursor);
        long pointerB = readPointer(cursor);
        short readChecksumB = readChecksum(cursor);
        short checksumB = checksumOf(generationB, pointerB);
        boolean correctChecksumB = readChecksumB == checksumB;

        byte pointerStateA =
                pointerState(stableGeneration, unstableGeneration, generationA, pointerA, correctChecksumA);
        byte pointerStateB =
                pointerState(stableGeneration, unstableGeneration, generationB, pointerB, correctChecksumB);

        long writeResult = writeResult(pointerStateA, pointerStateB, generationA, generationB);

        if (isSuccess(writeResult)) {
            boolean writeToA = (writeResult & SLOT_MASK) == FLAG_SLOT_A;
            int writeOffset = writeToA ? offset : offset + GenerationSafePointer.SIZE;
            cursor.setOffset(writeOffset);
            GenerationSafePointer.write(cursor, unstableGeneration, pointer);
        }
        return writeResult;
    }

    private static long writeResult(byte pointerStateA, byte pointerStateB, long generationA, long generationB) {
        if (pointerStateA == STABLE) {
            if (pointerStateB == STABLE) {
                if (generationA > generationB) {
                    // Write to slot B
                    return SUCCESS_WRITE_TO_B;
                } else if (generationB > generationA) {
                    // Write to slot A
                    return SUCCESS_WRITE_TO_A;
                }
            } else {
                // Write to slot B
                return SUCCESS_WRITE_TO_B;
            }
        } else if (pointerStateB == STABLE) {
            // write to slot A
            return SUCCESS_WRITE_TO_A;
        } else if (pointerStateA == UNSTABLE) {
            if (pointerStateB == EMPTY) {
                // write to slot A
                return SUCCESS_WRITE_TO_A;
            }
        } else if (pointerStateB == UNSTABLE) {
            if (pointerStateA == EMPTY) {
                // write to slot B
                return SUCCESS_WRITE_TO_B;
            }
        } else if (pointerStateA == EMPTY && pointerStateB == EMPTY) {
            // write to slot A
            return SUCCESS_WRITE_TO_A;
        }

        // Encode error
        return FLAG_FAIL
                | FLAG_WRITE
                | generationState(generationA, generationB)
                | ((long) pointerStateA) << SHIFT_STATE_A
                | ((long) pointerStateB) << SHIFT_STATE_B;
    }

    private static long generationState(long generationA, long generationB) {
        return generationA > generationB
                ? FLAG_GENERATION_A_BIG
                : generationB > generationA ? FLAG_GENERATION_B_BIG : FLAG_GENERATION_EQUAL;
    }

    /**
     * Pointer state of a GSP (generation, pointer, checksum). Can be any of:
     * <ul>
     * <li>{@link #STABLE}</li>
     * <li>{@link #UNSTABLE}</li>
     * <li>{@link #CRASH}</li>
     * <li>{@link #BROKEN}</li>
     * <li>{@link #EMPTY}</li>
     * </ul>
     *
     * @param stableGeneration stable generation.
     * @param unstableGeneration unstable generation.
     * @param generation GSP generation.
     * @param pointer GSP pointer.
     * @param checksumIsCorrect whether or not GSP checksum matches checksum of {@code generation} and {@code pointer}.
     * @return one of the available pointer states.
     */
    static byte pointerState(
            long stableGeneration, long unstableGeneration, long generation, long pointer, boolean checksumIsCorrect) {
        if (GenerationSafePointer.isEmpty(generation, pointer)) {
            return EMPTY;
        }
        if (!checksumIsCorrect) {
            return BROKEN;
        }
        if (generation < MIN_GENERATION) {
            return BROKEN;
        }
        if (generation <= stableGeneration) {
            return STABLE;
        }
        if (generation == unstableGeneration) {
            return UNSTABLE;
        }
        return CRASH;
    }

    /**
     * Checks to see if a result from read/write was successful. If not more failure information can be extracted
     * using {@link #failureDescription(long, long, String, long, long, String, String, int)}.
     *
     * @param result result from {@link #read(PageCursor, long, long, GBPTreeGenerationTarget)} or {@link #write(PageCursor, long, long, long)}.
     * @return {@code true} if successful read/write, otherwise {@code false}.
     */
    static boolean isSuccess(long result) {
        return (result & SUCCESS_MASK) == FLAG_SUCCESS;
    }

    /**
     * @param readResult whole read result from {@link #read(PageCursor, long, long, GBPTreeGenerationTarget)}, containing both
     * pointer as well as header information about the pointer.
     * @return the pointer-part of {@code readResult}.
     */
    static long pointer(long readResult) {
        return readResult & POINTER_MASK;
    }

    /**
     * NOTE! Use only with write cursor. For read cursor use {@link #failureDescription(long, long, String, long, long)}.
     *
     * Calling {@link #read(PageCursor, long, long, GBPTreeGenerationTarget)} (potentially also {@link #write(PageCursor, long, long, long)})
     * can fail due to seeing an unexpected state of the two GSPs. Failing right there and then isn't an option
     * due to how the page cache works and that something read from a {@link PageCursor} must not be interpreted
     * until after passing a {@link PageCursor#shouldRetry()} returning {@code false}. This creates a need for
     * including failure information in result returned from these methods so that, if failed, can have
     * the caller which interprets the result fail in a proper place. That place can make use of this method
     * by getting a human-friendly description about the failure.
     *
     * @param result result from {@link #read(PageCursor, long, long, GBPTreeGenerationTarget)} or
     * {@link #write(PageCursor, long, long, long)}.
     * @param nodeId The id of the node from which result was read.
     * @param pointerType Describing the pointer that was read, such as CHILD, RIGHT_SIBLING, LEFT_SIBLING, SUCCESSOR
     * @param stableGeneration The current stable generation of the tree.
     * @param unstableGeneration The current unstable generation of the tree.
     * @param gspStringA Full string representation of gsp state A.
     * @param gspStringB Full string representation of gsp state B.
     * @param offset The exact offset from which the gspp was read.
     * @return a human-friendly description of the failure.
     */
    static String failureDescription(
            long result,
            long nodeId,
            String pointerType,
            long stableGeneration,
            long unstableGeneration,
            String gspStringA,
            String gspStringB,
            int offset) {
        return failureDescription(
                result,
                nodeId,
                pointerType,
                stableGeneration,
                unstableGeneration,
                gspStringA,
                gspStringB,
                Integer.toString(offset));
    }

    /**
     * Useful for when reading with read cursor.
     * See {@link #failureDescription(long, long, String, long, long, String, String, int)}
     */
    static String failureDescription(
            long result, long nodeId, String pointerType, long stableGeneration, long unstableGeneration) {
        return failureDescription(result, nodeId, pointerType, stableGeneration, unstableGeneration, "", "", "UNKNOWN");
    }

    private static String failureDescription(
            long result,
            long nodeId,
            String pointerType,
            long stableGeneration,
            long unstableGeneration,
            String gspStringA,
            String gspStringB,
            String offsetString) {
        return "GSPP " + (isRead(result) ? "READ" : "WRITE") + " failure"
                + format("%n  Pointer[type=%s, offset=%s, nodeId=%d]", pointerType, offsetString, nodeId)
                + format(
                        "%n  Pointer state A: %s %s",
                        pointerStateName(pointerStateFromResult(result, SHIFT_STATE_A)), gspStringA)
                + format(
                        "%n  Pointer state B: %s %s",
                        pointerStateName(pointerStateFromResult(result, SHIFT_STATE_B)), gspStringB)
                + format("%n  stableGeneration=%d, unstableGeneration=%d", stableGeneration, unstableGeneration)
                + format("%n  Generations: " + generationComparisonFromResult(result));
    }

    /**
     * NOTE! In the case of exception, cursor will be used to read additional information from tree. If cursor only has read lock, use
     * {@link #assertSuccess(long, long, String, long, long)} instead.
     * Asserts that a result is {@link #isSuccess(long) successful}, otherwise throws {@link IllegalStateException}.
     *
     * @param result result returned from {@link #read(PageCursor, long, long, GBPTreeGenerationTarget)} or
     * {@link #write(PageCursor, long, long, long)}
     * @param nodeId The id of the node from which result was read.
     * @param pointerType Describing the pointer that was read, such as CHILD, RIGHT_SIBLING, LEFT_SIBLING, SUCCESSOR
     * @param stableGeneration The current stable generation of the tree.
     * @param unstableGeneration The current unstable generation of the tree.
     * @param cursor {@link PageCursor} placed at tree node from which result was read.
     * @param offset The exact offset from which the gspp was read.
     * @return {@code true} if {@link #isSuccess(long) successful}, for interoperability with {@code assert}.
     */
    static boolean assertSuccess(
            long result,
            long nodeId,
            String pointerType,
            long stableGeneration,
            long unstableGeneration,
            PageCursor cursor,
            int offset) {
        if (!isSuccess(result)) {
            cursor.setOffset(offset);

            // Try A
            long generationA = readGeneration(cursor);
            long pointerA = readPointer(cursor);
            short readChecksumA = readChecksum(cursor);
            short checksumA = checksumOf(generationA, pointerA);
            boolean correctChecksumA = readChecksumA == checksumA;

            // Try B
            long generationB = readGeneration(cursor);
            long pointerB = readPointer(cursor);
            short readChecksumB = readChecksum(cursor);
            short checksumB = checksumOf(generationB, pointerB);
            boolean correctChecksumB = readChecksumB == checksumB;

            String gspStringA = GenerationSafePointer.toString(generationA, pointerA, readChecksumA, correctChecksumA);
            String gspStringB = GenerationSafePointer.toString(generationB, pointerB, readChecksumB, correctChecksumB);

            String failureMessage = failureDescription(
                    result, nodeId, pointerType, stableGeneration, unstableGeneration, gspStringA, gspStringB, offset);
            throw new TreeInconsistencyException(failureMessage);
        }
        return true;
    }

    /**
     * See {@link #assertSuccess(long, long, String, long, long, PageCursor, int)}
     */
    static boolean assertSuccess(
            long result, long nodeId, String pointerType, long stableGeneration, long unstableGeneration) {
        if (!isSuccess(result)) {
            throw new TreeInconsistencyException(
                    failureDescription(result, nodeId, pointerType, stableGeneration, unstableGeneration));
        }
        return true;
    }

    private static String generationComparisonFromResult(long result) {
        long bits = result & GENERATION_COMPARISON_MASK;
        if (bits == FLAG_GENERATION_EQUAL) {
            return GENERATION_COMPARISON_NAME_EQUAL;
        } else if (bits == FLAG_GENERATION_A_BIG) {
            return GENERATION_COMPARISON_NAME_A_BIG;
        } else if (bits == FLAG_GENERATION_B_BIG) {
            return GENERATION_COMPARISON_NAME_B_BIG;
        } else {
            return "Unknown[" + bits + "]";
        }
    }

    /**
     * Name of the provided {@code pointerState} gotten from {@link #pointerState(long, long, long, long, boolean)}.
     *
     * @param pointerState pointer state to get name for.
     * @return name of {@code pointerState}.
     */
    static String pointerStateName(byte pointerState) {
        switch (pointerState) {
            case STABLE:
                return "STABLE";
            case UNSTABLE:
                return "UNSTABLE";
            case CRASH:
                return "CRASH";
            case BROKEN:
                return "BROKEN";
            case EMPTY:
                return "EMPTY";
            default:
                return "Unknown[" + pointerState + "]";
        }
    }

    static byte pointerStateFromResult(long result, int shift) {
        return (byte) ((result >>> shift) & STATE_MASK);
    }

    static boolean isRead(long result) {
        return (result & READ_OR_WRITE_MASK) == FLAG_READ;
    }

    static boolean resultIsFromSlotA(long result) {
        return (result & SLOT_MASK) == FLAG_SLOT_A;
    }
}
