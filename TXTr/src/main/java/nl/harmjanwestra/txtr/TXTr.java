package nl.harmjanwestra.txtr;

import org.apache.commons.cli.*;
import nl.harmjanwestra.utilities.legacy.genetica.io.Gpio;
import nl.harmjanwestra.utilities.legacy.genetica.io.text.TextFile;

import java.io.IOException;

/**
 * Created by Harm-Jan on 02/01/16.
 */
public class TXTr {
	
	private static Options OPTIONS;
	
	static {
		OPTIONS = new Options();
		
		Option option;
		
		option = Option.builder()
				.desc("Test GZipped file")
				.longOpt("testgz")
				.build();
		OPTIONS.addOption(option);
		
		option = Option.builder()
				.desc("Split textfile by lines")
				.longOpt("split")
				.build();
		OPTIONS.addOption(option);
		
		option = Option.builder()
				.desc("Multiple line hashtag delimited header")
				.longOpt("multilineheader")
				.build();
		OPTIONS.addOption(option);
		
		option = Option.builder()
				.desc("Merge while skipping first line of all files except 1st")
				.longOpt("merge")
				.build();
		OPTIONS.addOption(option);
		
		option = Option.builder("i")
				.desc("Input")
				.hasArg().required()
				.build();
		OPTIONS.addOption(option);
		
		option = Option.builder()
				.longOpt("comma")
				.desc("input is comma separated (in stead of a single path with locations)")
				.build();
		OPTIONS.addOption(option);
		
		option = Option.builder()
				.longOpt("pattern")
				.desc("input contains CHR pattern")
				.build();
		OPTIONS.addOption(option);
		
		
		option = Option.builder("n")
				.desc("Nr lines for splitting")
				.hasArg()
				.build();
		OPTIONS.addOption(option);
		
		option = Option.builder("o")
				.desc("Input")
				.hasArg().required()
				.build();
		OPTIONS.addOption(option);
	}
	
	public static void main(String[] args) {
		TXTr t = new TXTr();
		try {
			CommandLineParser parser = new DefaultParser();
			final CommandLine cmd = parser.parse(OPTIONS, args, true);
			
			String input = "";
			String output = "";
			boolean commaseparated = false;
			if (cmd.hasOption("i")) {
				input = cmd.getOptionValue("i");
			}
			if (cmd.hasOption("o")) {
				output = cmd.getOptionValue("o");
			}
			if (cmd.hasOption("comma")) {
				commaseparated = true;
			}
			
			if (cmd.hasOption("testgz")) {
				try {
					t.testGZ(input);
				} catch (IOException e) {
					e.printStackTrace();
				}
			} else if (cmd.hasOption("split")) {
				
				if (cmd.hasOption("n")) {
					try {
						int nrLines = Integer.parseInt(cmd.getOptionValue("n"));
						try {
							t.split(input, output, nrLines);
						} catch (IOException e) {
							e.printStackTrace();
						}
					} catch (NumberFormatException e) {
						System.out.println(cmd.getOptionValue("n") + " is not an integer");
					}
				} else {
					System.out.println("Use -n for --split");
					
					printHelp();
				}
			} else if (cmd.hasOption("merge")) {
				try {
					boolean multilineheader = false;
					if (cmd.hasOption("multilineheader")) {
						multilineheader = true;
					}
					t.mergeSkipHeader(input, output, multilineheader, commaseparated, cmd.hasOption("pattern"));
				} catch (IOException e) {
					e.printStackTrace();
				}
				
			}
			
			
		} catch (ParseException e) {
			printHelp();
			e.printStackTrace();
		}
		
	}
	
	private void testGZ(String input) throws IOException {
		TextFile tf = new TextFile(input, TextFile.R);
		int ctr = 0;
		try {
			while (tf.readLine() != null) {
				tf.readLine();
				ctr++;
				if (ctr % 1000 == 0) {
					System.out.print(ctr + " lines parsed...\r");
				}
			}
		} catch (Exception e) {
			System.out.println("File failed at line "+ctr);
			e.printStackTrace();
		}
		System.out.println();
		tf.close();
	}
	
	public static void printHelp() {
		HelpFormatter formatter = new HelpFormatter();
		formatter.printHelp(" ", OPTIONS);
		System.exit(-1);
	}
	
	public void split(String file, String fileout, int lns) throws IOException {
		
		TextFile in = new TextFile(file, TextFile.R);
		int ctr = 0;
		int ctr2 = 1;
		String ln = in.readLine();
		TextFile out = new TextFile(fileout + "-" + ctr2, TextFile.W);
		while (ln != null) {
			ln = in.readLine();
			out.writeln(ln);
			ctr++;
			if (ctr % lns == 0) {
				out.close();
				out = new TextFile(fileout + "-" + ctr2, TextFile.W);
				ctr2++;
			}
		}
		out.close();
		
		in.close();
	}
	
	public void mergeSkipHeader(String fileList, String output, boolean multilinehashtagheader, boolean commasep, boolean pattern) throws IOException {
		
		String[] files = null;
		if (pattern) {
			files = new String[22];
			for (int i = 1; i < 23; i++) {
				files[i - 1] = fileList.replaceAll("CHR", "" + i);
			}
		} else if (commasep) {
			files = fileList.split(",");
		} else {
			TextFile tf1 = new TextFile(fileList, TextFile.R);
			files = tf1.readAsArray();
			tf1.close();
		}
		
		boolean headerwritten = false;
		
		TextFile out = new TextFile(output, TextFile.W);
		int fctr = 0;
		for (String file : files) {
			if (Gpio.exists(file)) {
				TextFile in = new TextFile(file, TextFile.R);
				String ln = in.readLine();
				int lnctr = 0;
				while (ln != null) {
					if (multilinehashtagheader) {
						if (ln.startsWith("#")) {
							if (fctr == 0) {
								out.writeln(ln);
							}
						} else {
							out.writeln(ln);
						}
					} else {
						if (!headerwritten && lnctr == 0) {
							out.writeln(ln);
							headerwritten = true;
						} else if (lnctr > 0) {
							out.writeln(ln);
						}
					}
					
					ln = in.readLine();
					lnctr++;
				}
				
				in.close();
				fctr++;
			} else {
				System.out.println("Warning - could not find file: " + file);
			}
			
		}
		out.close();
		
	}
	
}
