import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Formatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MIDIFlipper {
	private static String inputFileName = null;
	private static String outputFileName = null;
	private static File outputFile = null;
	private static BufferedInputStream inputFileStream = null;
	private static BufferedOutputStream outputFileStream = null;
	private static boolean preserveOctaves = false;
	private static boolean sameFile = false;
	private static byte[] validHeader = {
		'M','T','h','d',0x00,0x00,0x00,0x06
	};
	private static byte[] validTrackHeader = {
		'M','T','r','k'
	};
	private static byte[][] tracks = null;
	private static byte[][] trackOffsets = null;

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		if (args.length == 1 && !args[0].equals("-preserveOctaves")) {
			inputFileName = args[0];
			System.out.println("Input file: "+inputFileName);
			// No output specified, create one.
			outputFileName = createOutputFileName(inputFileName, false);
			System.out.println("Output file not specified; will output at: "+outputFileName);
		}
		else if (args.length == 2) {
			if (args[0].equals("-preserveOctaves")) {
				inputFileName = args[1];
				System.out.println("Input file: "+inputFileName);
				// No output specified, create one.
				outputFileName = createOutputFileName(inputFileName, true);
				System.out.println("Output file not specified; will output at: "+outputFileName);
				preserveOctaves = true;
			} else if (args[1].equals("-preserveOctaves")) {
				inputFileName = args[0];
				System.out.println("Input file: "+inputFileName);
				// No output specified, create one.
				outputFileName = createOutputFileName(inputFileName, true);
				System.out.println("Output file not specified; will output at: "+outputFileName);
				preserveOctaves = true;
			} else {
				inputFileName = args[0];
				System.out.println("Input file: "+inputFileName);
				outputFileName = args[1];
				System.out.println("Output file: "+outputFileName);
				if (inputFileName.equals(outputFileName)) {
					sameFile = true;
				}
			}
		} else if (args.length == 3) {
			if (args[0].equals("-preserveOctaves")) {
				inputFileName = args[1];
				System.out.println("Input file: "+inputFileName);
				outputFileName = args[2];
				System.out.println("Output file: "+outputFileName);
				if (inputFileName.equals(outputFileName)) {
					sameFile = true;
				}
				preserveOctaves = true;
			} else if (args[1].equals("-preserveOctaves")) {
				inputFileName = args[0];
				System.out.println("Input file: "+inputFileName);
				outputFileName = args[2];
				System.out.println("Output file: "+outputFileName);
				if (inputFileName.equals(outputFileName)) {
					sameFile = true;
				}
				preserveOctaves = true;
			} else if (args[2].equals("-preserveOctaves")) {
				inputFileName = args[0];
				System.out.println("Input file: "+inputFileName);
				outputFileName = args[1];
				System.out.println("Output file: "+outputFileName);
				if (inputFileName.equals(outputFileName)) {
					sameFile = true;
				}
				preserveOctaves = true;
			} else {
				System.out.println("Usage: MIDIParser [input file] ([output file]) (-preserveOctaves)");
				System.exit(0);
			}
		} else {
			System.out.println("Usage: MIDIParser [input file] ([output file]) (-preserveOctaves)");
			System.exit(0);
		}

		try {
			inputFileStream = new BufferedInputStream(new FileInputStream(inputFileName));
			// Verify the MIDI file is valid (check header, should be "0x4D54686400000006")
			byte[] header = new byte[8];
			inputFileStream.read(header);
			if (!Arrays.equals(header, validHeader)) {
				// Not a MIDI file.
				System.out.println("Invalid MIDI file! (Expected 0x"
						+bytesToHexString(validHeader)+", got 0x"
						+bytesToHexString(header)+")");
				return;
			}
			// File is a valid MIDI file; might as well open the output file then.
			if (!sameFile) {
				outputFile = new File(outputFileName);
			} else {
				outputFile = File.createTempFile("tempMIDI", "mid");
				outputFile.deleteOnExit();
			}
			outputFileStream = new BufferedOutputStream(new FileOutputStream(outputFile));
			// Get some info about the MIDI (mostly the number of tracks)
			byte[] formatTypeBytes = new byte[2];
			inputFileStream.read(formatTypeBytes);
			byte[] numberTracksBytes = new byte[2];
			inputFileStream.read(numberTracksBytes);
			byte[] timeDivisionBytes = new byte[2];
			inputFileStream.read(timeDivisionBytes);
			// Divide up the tracks, as long as we're at it
			int numberTracks = twoByteArrayToInt(numberTracksBytes);
			tracks = new byte[numberTracks][];
			// Write all this stuff to the output file
			outputFileStream.write(header);
			outputFileStream.write(formatTypeBytes);
			outputFileStream.write(numberTracksBytes);
			outputFileStream.write(timeDivisionBytes);
			// Now let's loop through all the tracks
			readTracks();
			trackOffsets = new byte[tracks.length][16];
			evaluateTracks();
			parseTracks();
			// Finally, write all the data to the output.
			for (byte[] track : tracks) {
				outputFileStream.write(track);
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			// Close the streams
			try {
				if (inputFileStream != null) {
					inputFileStream.close();
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
			try {
				if (outputFileStream != null) {
					outputFileStream.close();
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
			
			// If the input/output was the same, move the contents from the temp file to the destination file
			if (sameFile) {
				try {
					inputFileStream = new BufferedInputStream(new FileInputStream(outputFile));
					outputFileStream = new BufferedOutputStream(new FileOutputStream(outputFileName));
					while (inputFileStream.available() > 0) {
						outputFileStream.write(inputFileStream.read());
					}
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} finally {
					try {
						if (inputFileStream != null) {
							inputFileStream.close();
						}
					} catch (Exception e) {
						e.printStackTrace();
					}
					try {
						if (outputFileStream != null) {
							outputFileStream.close();
						}
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			}
			System.out.println("Exiting...");
			// Exit
			System.exit(0);
		}
	}

	private static String createOutputFileName(String inputFileName, boolean preserveOctaves) {
		// Split into the file path, the short file name, and the extension.
		Pattern filePattern = Pattern.compile("^(.*[\\\\/])?(.+?)(\\.[^.]+)?$");
		Matcher matcher = filePattern.matcher(inputFileName);
		if (matcher.find()) {
			String path = matcher.group(1);
			String name = matcher.group(2);
			String extension = matcher.group(3);
			// Now concatenate the new name.
			String outputFileName = "";
			if (path != null) {
				outputFileName += path;
			}
			outputFileName += name + " (flipped" + (preserveOctaves ? ", octaves kept)" : ")");
			if (extension != null) {
				outputFileName += extension;
			} else {
				outputFileName += ".mid";
			}
			return outputFileName;
		} else {
			// Apparently my regular expression can't handle this file. Complain.
			System.out.println("Cannot parse format of input file path!\n"
					+"Bug Shadow Hog to run regular expression \""
					+filePattern+"\" against \""+inputFileName+"\"");
			System.exit(0);
		}
		return null;
	}

	private static int twoByteArrayToInt(byte[] array) {
		if (array.length != 2) {
			throw new UnsupportedOperationException("Input MUST be 2 bytes long; this was "+array.length+" bytes");
		}
		int byte0 = ((int)(array[0]) & 0x00FF)<<8;
		int byte1 = ((int)(array[1]) & 0x00FF);
		int value = byte0 + byte1;
		return value;
	}

	private static long fourByteArrayToLong(byte[] array) {
		if (array.length != 4) {
			throw new UnsupportedOperationException("Input MUST be 4 bytes long; this was "+array.length+" bytes");
		}
		long byte0 = ((long)(array[0]) & 0x000000FF)<<24;
		long byte1 = ((long)(array[1]) & 0x000000FF)<<16;
		long byte2 = ((long)(array[2]) & 0x000000FF)<<8;
		long byte3 = ((long)(array[3]) & 0x000000FF);
		long value = byte0 + byte1 + byte2 + byte3;
		return value;
	}

	// From http://www.coderanch.com/t/526487/java/java/Java-Byte-Hex-String
	public static String bytesToHexString(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);

		Formatter formatter = new Formatter(sb);
		for (byte b : bytes) {
			formatter.format("%02x", b);
		}
		formatter.close();

		return sb.toString();
	}

	private static byte[] readVariableLength(byte[] input, int position) {
		int length = 0;
		byte current = input[position];
		while ((current & 0x80) != 0 && length < 4) {
			length++;
			current = input[position+length];
		}
		length++;
		byte[] variableLengthArray = Arrays.copyOfRange(input, position, position+length);
		return variableLengthArray;
	}

	private static int getValueOfVariableLength(byte[] array) {
		int overall = 0;
		for (int i = 0; i < array.length; i++) {
			overall += ((int)(array[i] & 0x7F) << (7 * ((array.length - 1) - i)));
		}
		return overall;
	}

	private static void readTracks() throws IOException {
		for (int i = 0; i < tracks.length; i++) {
			inputFileStream.mark(8);
			byte[] header = new byte[4];
			byte[] lengthBytes = new byte[4];
			inputFileStream.read(header);
			inputFileStream.read(lengthBytes);
			// Double-check that this is aligned correctly
			if (!Arrays.equals(header, validTrackHeader)) {
				// Not a MIDI file.
				System.out.println("Track is invalid! (Expected 0x"
						+bytesToHexString(validTrackHeader)+", got 0x"
						+bytesToHexString(header)+")");
				return;
			}
			// If so, get the length of the track.
			long length = fourByteArrayToLong(lengthBytes);
			if ((length & 0x80000000) != 0) {
				throw new UnsupportedOperationException("This track has "+length
						+" bytes, which is over the maximum of "+0x7FFFFFFF
						+" that Java supports! (Also, your MIDI is 2GB or more in size, somehow.)");
			}
			tracks[i] = new byte[(int)(length+8)];
			// Now read the track in.
			inputFileStream.reset();
			inputFileStream.read(tracks[i]);
			//System.out.println("Track "+(i+1)+" read");
		}
	}

	private static void parseTracks() {
		byte[] currentRPN = {(byte)0x00, (byte)0x00};
		for (int i = 0; i < tracks.length; i++) {
			byte[] track = tracks[i];
			int position = 8; // skip the header
			byte previousEvent = 0x00;
			while (position < track.length) {
				// Read delta
				byte[] delta = readVariableLength(track, position);
				position += delta.length;
				// Get message type
				byte eventByte = track[position];
				if ((eventByte & 0x80) != 0) {
					// Most significant bit is set; this is an event byte, so move the cursor forward
					position++;
				} else {
					// Most significant bit is not set; this uses the previous event byte, so use that and reread this
					eventByte = previousEvent;
				}
				if (eventByte == (byte)0xF0) {
					// SysEx event; loop until you read 0xF7, and assume that's the end
					System.out.println("Attempting SysEx");
					do {
						position++;
						eventByte = track[position];
					} while (eventByte != (byte)0xF7);
					// Found it
					position++;
				} else if (eventByte == (byte)0xFF) {
					// Meta Event; read how much to skip and skip it
					//System.out.println("Reading Meta Event");
					position+=1; // Skip the Meta Event type
					byte[] lengthBytes = readVariableLength(track,position);
					int length = getValueOfVariableLength(lengthBytes);
					position+=lengthBytes.length+length;
				} else {
					byte instruction = (byte)((eventByte & 0xF0) >> 4);
					byte channel = (byte)((eventByte & 0x0F));
					//System.out.println("Reading standard event "+String.format("%x", instruction)+" on channel "+channel);
					if (instruction == 0x8
							|| instruction == 0x9
							|| instruction == 0xA) {
						// Instruction is Note On/Off/Aftertouch
						if (channel != 9) { // Don't change drums!
							byte note = track[position];
							byte newNote = (byte)(note ^ 0x7F); // invert 0-127 range
							if (preserveOctaves) {
								if (Math.abs(trackOffsets[i][channel])!=11) {
									newNote += 12 * trackOffsets[i][channel];
									while ((newNote & 0x80) != 0) {
										newNote -= 12;
									}
								}
							}
							track[position] = newNote; // Assign the new value
						}
						position += 2; // Don't care about the velocity; move to the next delta
					} else if (instruction == 0xB) {
						// Get the controller
						byte controllerType = track[position];
						position++;
						if (controllerType == (byte)0x64) { // RPN LSB
							currentRPN[1] = track[position];
						} else if (controllerType == (byte)0x65) { // RPN MSB
							currentRPN[0] = track[position];
						} else if (controllerType == (byte)0x06
								|| controllerType == (byte)0x26) { // values for the RPN (MSB/LSB, respectively)
							if (getValueOfVariableLength(currentRPN) == 1
									|| getValueOfVariableLength(currentRPN) == 2) {
								byte value = track[position];
								byte newValue = (byte)(value ^ 0x7F);
								track[position] = newValue;
							}
						}
						position++;
					} else if (instruction == 0xC
							|| instruction == 0xD) {
						// Don't care to tweak this
						//System.out.println("Encountered an 0x"+String.format("%x", instruction)+" argument "+position+" bytes into track "+i+"; check if it's one or two bytes afterward!");
						position += 1;
					} else if (instruction == 0xE) {
						// Pitch bends! :D
						byte lsb = track[position];
						byte msb = track[position+1];
						int pitchBend = ((msb & 0x7F) << 7) + ((lsb & 0x7F));
						int newPitchBend = (pitchBend ^ 0x3FFF);
						byte newLSB = (byte)(newPitchBend & 0x7F);
						byte newMSB = (byte)((newPitchBend >> 7) & 0x7F);
						track[position] = newLSB;
						track[position+1] = newMSB;
						position+=2;
					} else {
						System.out.println("Invalid MIDI instruction 0x"
								+String.format("%x", instruction)
								+"; cannot proceed on this track");
						break;
					}
				}
				previousEvent = eventByte;
			}
			System.out.println("Track "+(i+1)+" reformatted");
		}
	}

	private static void evaluateTracks() {
		for (int i = 0; i < tracks.length; i++) {
			byte[] maxes = {0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0};
			byte[] mins = {127,127,127,127,127,127,127,127,127,127,127,127,127,127,127,127};
			byte[] track = tracks[i]; // Get the track
			int position = 8; // skip the header
			byte previousEvent = 0x00;
			while (position < track.length) {
				// Read delta
				byte[] delta = readVariableLength(track, position);
				position += delta.length;
				// Get message type
				byte eventByte = track[position];
				if ((eventByte & 0x80) != 0) {
					// Most significant bit is set; this is an event byte, so move the cursor forward
					position++;
				} else {
					// Most significant bit is not set; this uses the previous event byte, so use that and reread this
					eventByte = previousEvent;
				}
				if (eventByte == (byte)0xF0) {
					// SysEx event; loop until you read 0xF7, and assume that's the end
					System.out.println("Attempting SysEx");
					do {
						position++;
						eventByte = track[position];
					} while (eventByte != (byte)0xF7);
					position++;	// Found it
				} else if (eventByte == (byte)0xFF) {
					// Meta Event; read how much to skip and skip it
					position+=1; // Skip the Meta Event type
					byte[] lengthBytes = readVariableLength(track,position);
					int length = getValueOfVariableLength(lengthBytes);
					position+=lengthBytes.length+length;
				} else {
					byte instruction = (byte)((eventByte & 0xF0) >> 4);
					byte channel = (byte)((eventByte & 0x0F));
					//System.out.println("Reading standard event "+String.format("%x", instruction)+" on channel "+channel);
					if (instruction == 0x8
							|| instruction == 0x9
							|| instruction == 0xA) {
						// Instruction is Note On/Off/Aftertouch
						byte note = track[position];
						if (note > maxes[channel]) maxes[channel] = note;
						if (note < mins [channel]) mins[channel] = note;
						position += 2; // Don't care about the velocity; move to the next delta
					} else if (instruction == 0xB
							|| instruction == 0xE) {
						position += 2;
					} else if (instruction == 0xC
							|| instruction == 0xD) {
						position += 1;
					} else {
						System.out.println("Invalid MIDI instruction 0x"
								+String.format("%x", instruction)
								+"; cannot proceed on this track");
						break;
					}
				}
				previousEvent = eventByte;
			}
			// TODO: Average mins/maxes out, and figure out how many octaves (12 semitones) to offset it by)
			for (int j = 0; j < 16; j++) {
				double average = (mins[j] + maxes[j]) / 2.0;
				double newAverage = ((mins[j] ^ 0x7F) + (maxes[j] ^ 0x7F)) / 2.0;
				double difference = average - newAverage;
				byte numOctaves = (byte) Math.round(difference / 12);
				if (difference % 6 == 0 && difference % 12 != 0 && numOctaves % 2 != 0) {
					numOctaves--;
				}
				trackOffsets[i][j] = numOctaves;
			}
			System.out.println("Track "+(i+1)+" analyzed");
		}
	}
}
