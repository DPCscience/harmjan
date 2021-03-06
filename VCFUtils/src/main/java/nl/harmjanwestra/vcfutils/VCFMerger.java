package nl.harmjanwestra.vcfutils;

import com.gs.collections.api.list.MutableList;
import com.gs.collections.impl.multimap.list.FastListMultimap;
import nl.harmjanwestra.utilities.enums.Chromosome;
import nl.harmjanwestra.utilities.features.Feature;
import nl.harmjanwestra.utilities.features.FeatureComparator;
import nl.harmjanwestra.utilities.genotypes.GenotypeTools;
import nl.harmjanwestra.utilities.vcf.VCFFunctions;
import nl.harmjanwestra.utilities.vcf.VCFGenotypeData;
import nl.harmjanwestra.utilities.vcf.VCFVariant;
import nl.harmjanwestra.utilities.vcf.VCFVariantLoader;
import nl.harmjanwestra.utilities.vcf.filter.variantfilters.VCFVariantFilters;
import nl.harmjanwestra.utilities.vcf.filter.variantfilters.VCFVariantImpQualFilter;
import nl.harmjanwestra.utilities.legacy.genetica.console.ProgressBar;
import nl.harmjanwestra.utilities.legacy.genetica.containers.Pair;
import nl.harmjanwestra.utilities.legacy.genetica.io.Gpio;
import nl.harmjanwestra.utilities.legacy.genetica.io.text.TextFile;
import nl.harmjanwestra.utilities.legacy.genetica.text.Strings;
import nl.harmjanwestra.utilities.legacy.genetica.util.Primitives;

import java.io.IOException;
import java.util.*;

/**
 * Created by hwestra on 9/25/15.
 */
public class VCFMerger {
	
	
	public void concatenate(String vcf, String out) throws IOException {
		
		String[] list = vcf.split(",");
		if (list.length == 1) {
			if (vcf.contains("CHR")) {
				ArrayList<String> listArr = new ArrayList<>();
				for (int i = 1; i < 23; i++) {
					String rep = vcf.replaceAll("CHR", "" + i);
					if (Gpio.exists(rep)) {
						listArr.add(rep);
					}
				}
				list = listArr.toArray(new String[0]);
				System.out.println(list.length + " files to concat.");
			}
			
		}
		
		
		String tmp = out + "_tmp1.vcf.gz";
		concatenate(list[0], list[1], tmp);
		
		String tmp2 = out + "_tmp2.vcf.gz";
		for (int i = 2; i < list.length; i++) {
			concatenate(tmp, list[i], tmp2);
			Gpio.moveFile(tmp2, tmp);
		}
		
		Gpio.moveFile(tmp, out);
		
	}
	
	/*
	Concatenate variants for samples that are present in both VCF files
	 */
	public void concatenate(String vcf1, String vcf2, String out) throws IOException {
		
		System.out.println("in1: " + vcf1);
		System.out.println("in2: " + vcf2);
		System.out.println("out: " + out);
		
		VCFFunctions functions = new VCFFunctions();
		ArrayList<String> samples1 = functions.getVCFSamples(vcf1);
		System.out.println(samples1.size() + " samples in VCF1");
		ArrayList<String> samples2 = functions.getVCFSamples(vcf2);
		System.out.println(samples1.size() + " samples in VCF2");
		
		// determine shared samples
		HashSet<String> samples1Hash = new HashSet<String>();
		samples1Hash.addAll(samples1);
		HashMap<String, Integer> shared = new HashMap<String, Integer>();
		int ctr = 0;
		for (String s : samples2) {
			if (samples1Hash.contains(s)) {
				if (!shared.containsKey(s)) {
					shared.put(s, ctr);
					ctr++;
				}
			}
		}
		System.out.println(shared.size() + " shared samples");
		if (shared.isEmpty()) {
			System.out.println("ERROR: no shared samples");
			System.exit(-1);
		}
		
		// index the samples in both datasets..
		int[] index1 = new int[samples1.size()];
		int[] index2 = new int[samples2.size()];
		for (int i = 0; i < samples1.size(); i++) {
			Integer index = shared.get(samples1.get(i));
			if (index == null) {
				index = -1;
			}
			index1[i] = index;
			
		}
		
		for (int i = 0; i < samples2.size(); i++) {
			Integer index = shared.get(samples2.get(i));
			if (index == null) {
				index = -1;
			}
			index2[i] = index;
		}
		
		
		// get a list of variants in path 1
		TextFile tf = new TextFile(vcf1, TextFile.R);
		String ln = tf.readLine();
		HashSet<String> variants1 = new HashSet<String>();
		
		int lnctr = 0;
		while (ln != null) {
			if (!ln.startsWith("#")) {
				String substr = ln.substring(0, 200);
				VCFVariant v1 = new VCFVariant(substr, VCFVariant.PARSE.HEADER);
				variants1.add(v1.toString());
			}
			lnctr++;
			if (lnctr % 1000 == 0) {
				System.out.print(lnctr + " lines read\r");
			}
			ln = tf.readLine();
		}
		tf.close();
		
		System.out.println(variants1.size() + " variants in VCF1");
		
		TextFile outf = new TextFile(out, TextFile.W);
		TextFile tf2 = new TextFile(vcf2, TextFile.R);
		
		HashSet<String> sharedVariants = new HashSet<String>();
		
		ln = tf2.readLine();
		int written = 0;
		while (ln != null) {
			if (ln.startsWith("##")) {
				outf.writeln(ln);
			} else if (ln.startsWith("#CHROM")) {
				// do some header magic
				String[] elems = ln.split("\t");
				String header = elems[0];
				for (int i = 1; i < 9; i++) {
					header += "\t" + elems[i];
				}
				String[] remaining = new String[shared.size()];
				for (int i = 9; i < elems.length; i++) {
					int index = index2[i - 9];
					if (index != -1) {
						remaining[index] = elems[i];
					}
				}
				// check whether there are nulls
				for (int i = 0; i < remaining.length; i++) {
					if (remaining[i] == null) {
						System.err.println("Error: there should not be nulls here.. Are there duplicate samples?");
						System.exit(-1);
					}
				}
				outf.writeln(header + "\t" + Strings.concat(remaining, Strings.tab));
			} else if (ln.startsWith("#")) {
				outf.writeln(ln);
			} else {
				
				// variant
				String substr = ln.substring(0, 200);
				VCFVariant variant = new VCFVariant(substr, VCFVariant.PARSE.HEADER);
				// check whether variant is also in VCF1
				if (variants1.contains(variant.toString())) {
					sharedVariants.add(variant.toString());
				}
				
				// even if the variant is shared, print this version of the variant
				String[] elems = ln.split("\t");
				String header = elems[0];
				for (int i = 1; i < 9; i++) {
					header += "\t" + elems[i];
				}
				String[] remaining = new String[shared.size()];
				for (int i = 9; i < elems.length; i++) {
					int index = index2[i - 9];
					if (index != -1) {
						remaining[index] = elems[i];
					}
				}
				// check whether there are nulls
				for (int i = 0; i < remaining.length; i++) {
					if (remaining[i] == null) {
						System.err.println("Error: there should not be nulls here.. Are there duplicate samples?");
						System.exit(-1);
					}
				}
				outf.writeln(header + "\t" + Strings.concat(remaining, Strings.tab));
				written++;
				if (written % 1000 == 0) {
					System.out.print(written + " lines written from VCF2\r");
				}
			}
			ln = tf2.readLine();
		}
		
		tf2.close();
		System.out.println(written + " total variants written from VCF2");
		System.out.println(sharedVariants.size() + " shared variants");
		
		// now write the variants unique to path 1
		TextFile tf1 = new TextFile(vcf1, TextFile.R);
		int written1 = 0;
		ln = tf1.readLine();
		while (ln != null) {
			if (ln.startsWith("#")) {
			
			} else {
				// variant
				VCFVariant variant = new VCFVariant(ln, VCFVariant.PARSE.HEADER);
				if (!sharedVariants.contains(variant.toString())) {
					String[] elems = ln.split("\t");
					String header = elems[0];
					for (int i = 1; i < 9; i++) {
						header += "\t" + elems[i];
					}
					String[] remaining = new String[shared.size()];
					for (int i = 9; i < elems.length; i++) {
						int index = index1[i - 9];
						if (index != -1) {
							remaining[index] = elems[i];
						}
					}
					// check whether there are nulls
					for (int i = 0; i < remaining.length; i++) {
						if (remaining[i] == null) {
							System.err.println("Error: there should not be nulls here.. Are there duplicate samples?");
							System.exit(-1);
						}
					}
					outf.writeln(header + "\t" + Strings.concat(remaining, Strings.tab));
					written1++;
					if (written1 % 1000 == 0) {
						System.out.print(written1 + " lines written from VCF1\r");
					}
				}
				
			}
			ln = tf1.readLine();
		}
		
		tf1.close();
		
		
		outf.close();
		
		System.out.println(written1 + " written from VCF1");
		
	}
	
	/*
	This merges samples from two VCF files if there are variants that are overlapping. Non-overlapping variants are excluded.
	 */
	public void mergeAndIntersect(boolean linux,
								  int chrint,
								  String vcfsort,
								  String refVCF,
								  String testVCF,
								  String matchedPanelsOut,
								  boolean keepoverlapping,
								  String separator) throws IOException {
		Chromosome chr = Chromosome.parseChr("" + chrint);
		
		mergeAndIntersectVCFVariants(
				refVCF,
				testVCF,
				matchedPanelsOut + "ref-matched-" + chr.getName() + ".vcf.gz",
				matchedPanelsOut + "test-matched-" + chr.getName() + ".vcf.gz",
				matchedPanelsOut + "ref-test-merged-" + chr.getName() + ".vcf.gz",
				separator,
				matchedPanelsOut + "mergelog-" + chr.getName() + ".txt",
				keepoverlapping);
		
		VCFFunctions t = new VCFFunctions();
		t.sortVCF(linux, vcfsort, matchedPanelsOut + "ref-matched-" + chr.getName() + ".vcf.gz", matchedPanelsOut + "ref-matched-sorted-" + chr.getName() + ".vcf.gz", matchedPanelsOut + "ref-sort-" + chr.getName() + ".sh");
		t.sortVCF(linux, vcfsort, matchedPanelsOut + "test-matched-" + chr.getName() + ".vcf.gz", matchedPanelsOut + "test-matched-sorted-" + chr.getName() + ".vcf.gz", matchedPanelsOut + "test-sort-" + chr.getName() + ".sh");
		t.sortVCF(linux, vcfsort, matchedPanelsOut + "ref-test-merged-" + chr.getName() + ".vcf.gz", matchedPanelsOut + "ref-test-merged-sorted-" + chr.getName() + ".vcf.gz", matchedPanelsOut + "test-sort-" + chr.getName() + ".sh");
	}
	
	/*
	This merges two VCF files if there are overlapping samples, for those variants that are overlapping
	 */
	private void mergeAndIntersectVCFVariants(String refVCF,
											  String testVCF,
											  String vcf1out,
											  String vcf2out,
											  String vcfmergedout,
											  String separatorInMergedFile,
											  String logoutfile,
											  boolean keepNonOverlapping) throws IOException {
		
		System.out.println("Merging: ");
		System.out.println("ref: " + refVCF);
		System.out.println("test: " + testVCF);
		System.out.println("out: " + vcfmergedout);
		
		
		TextFile tf = new TextFile(refVCF, TextFile.R);
		TextFile mergedOut = new TextFile(vcfmergedout, TextFile.W);
		TextFile vcf1OutTf = new TextFile(vcf1out, TextFile.W);
		
		String header = "";
		String ln = tf.readLine();
		HashSet<String> headerLines = new HashSet<String>();
		while (ln != null) {
			if (ln.startsWith("##")) {
				
				
				if (!headerLines.contains(ln)) {
					if (header.length() == 0) {
						header += ln;
					} else {
						header += "\n" + ln;
					}
					vcf1OutTf.writeln(ln);
					headerLines.add(ln);
				}
			} else if (ln.startsWith("#")) {
				vcf1OutTf.writeln(ln);
			} else {
				break;
			}
			
			ln = tf.readLine();
		}
		tf.close();
		
		TextFile tf2 = new TextFile(testVCF, TextFile.R);
		TextFile vcf2OutTf = new TextFile(vcf2out, TextFile.W);
		ln = tf2.readLine();
		while (ln != null) {
			if (ln.startsWith("##")) {
				
				if (!headerLines.contains(ln)) {
					if (header.length() == 0) {
						header += ln;
					} else {
						header += "\n" + ln;
					}
					vcf2OutTf.writeln(ln);
					headerLines.add(ln);
				}
				
			} else if (ln.startsWith("#")) {
				vcf2OutTf.writeln(ln);
			} else {
				break;
			}
			
			ln = tf2.readLine();
		}
		tf2.close();
		
		VCFFunctions t = new VCFFunctions();
		
		ArrayList<String> samples1 = t.getVCFSamples(refVCF);
		ArrayList<String> samples2 = t.getVCFSamples(testVCF);
		
		// #CHROM  POS     ID      REF     ALT     QUAL    FILTER  INFO    FORMAT  SAMPLE1   SAMPLE2
		String sampleheaderLn = "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT";
		for (String s : samples1) {
			sampleheaderLn += "\t" + s;
		}
		for (String s : samples2) {
			sampleheaderLn += "\t" + s;
		}
		
		mergedOut.writeln(header + "\n" + sampleheaderLn);
		
		
		// reload the variants, get the positions with multiple variants at that position


//		VCFGenotypeData iterator = new VCFGenotypeData(refVCF);
//		VCFGenotypeData iterator2 = new VCFGenotypeData(testVCF);
		
		HashSet<Chromosome> chromosomes = new HashSet<Chromosome>();
		
		HashSet<Feature> allFeatures = new HashSet<Feature>();
		System.out.println("Inventorizing variants in: " + refVCF);
		TextFile refTf = new TextFile(refVCF, TextFile.R);
		String refLn = refTf.readLine();
		
		HashSet<Feature> refFeatures = new HashSet<Feature>();
		
		System.out.println("Inventorizing variants in: " + testVCF);
		TextFile testTf = new TextFile(testVCF, TextFile.R);
		String testLn = testTf.readLine();
		HashSet<Feature> testFeatures = new HashSet<Feature>();
		int ctr1 = 0;
		while (testLn != null) {
			if (!testLn.startsWith("#")) {
				VCFVariant var = new VCFVariant(testLn, VCFVariant.PARSE.HEADER);
				chromosomes.add(var.getChrObj());
				allFeatures.add(var.asFeature());
				testFeatures.add(var.asFeature());
			}
			ctr1++;
			if (ctr1 % 10000 == 0) {
				System.out.print(ctr1 + " lines parsed\r.");
			}
			testLn = testTf.readLine();
		}
		System.out.println(allFeatures.size() + " features after loading test vcf");
		
		ctr1 = 0;
		while (refLn != null) {
			if (!refLn.startsWith("#")) {
				VCFVariant var = new VCFVariant(refLn, VCFVariant.PARSE.HEADER);
				if (keepNonOverlapping || testFeatures.contains(var.asFeature())) {
					allFeatures.add(var.asFeature());
					chromosomes.add(var.getChrObj());
					refFeatures.add(var.asFeature());
				}
			}
			ctr1++;
			if (ctr1 % 10000 == 0) {
				System.out.print(ctr1 + " lines parsed\r.");
			}
			refLn = refTf.readLine();
		}
		System.out.println();
		System.out.println(refFeatures.size() + " features after loading ref vcf");
		System.out.println(allFeatures.size() + " total features");
		
		refTf.close();
		
		
		ArrayList<Feature> allFeatureArr = new ArrayList<Feature>();
		allFeatureArr.addAll(allFeatures);
		Collections.sort(allFeatureArr, new FeatureComparator(false));
		
		HashSet<Feature> intersect = new HashSet<Feature>();
		for (Feature f : testFeatures) {
			if (refFeatures.contains(f)) {
				intersect.add(f);
			}
		}
		
		System.out.println(intersect.size() + " overlapping features.");
		
		TextFile logOut = new TextFile(logoutfile, TextFile.W);
		logOut.writeln("chr\t" +
				"pos\t" +
				"refVariant#atPos\t" +
				"refVariantId\t" +
				"refVariantRefAllele\t" +
				"refVariantAltAlleles\t" +
				"refVariantMinorAllele\t" +
				"refVariantMAF\t" +
				"restVariant#atPos\t" +
				"testVariantId\t" +
				"testVariantRefAllele\t" +
				"testVariantAltAlleles\t" +
				"testVariantMinorAllele\t" +
				"testVariantMAF\t" +
				"testVariantRefAlleleAM\t" +
				"testVariantAltAllelesAM\t" +
				"testVariantMinorAlleleAM\t" +
				"Reason");
		
		TextFile uniqueRef = new TextFile(vcf1out + "-uniqueVars.txt", TextFile.W);
		TextFile uniqueTest = new TextFile(vcf2out + "-uniqueVars.txt", TextFile.W);
		for (Chromosome chr : chromosomes) {
			ArrayList<Feature> chrFeatures = new ArrayList<Feature>();
			HashSet<Feature> chrFeatureSet = new HashSet<Feature>();
			for (Feature f : allFeatureArr) {
				if (f.getChromosome().equals(chr)) {
					chrFeatures.add(f);
					chrFeatureSet.add(f);
				}
			}
			
			
			// load the genotypes..
			System.out.println("loading variants from: " + refVCF);
			HashSet<Feature> select = null;
			if (!keepNonOverlapping) {
				select = intersect;
			}
			FastListMultimap<Feature, VCFVariant> refVariantMap = t.getVCFVariants(refVCF, chrFeatureSet, select, true);
			System.out.println("loading variants from: " + testVCF);
			FastListMultimap<Feature, VCFVariant> testVariantMap = t.getVCFVariants(testVCF, chrFeatureSet, select, true);
			
			System.out.println(chr.getName() + "\t" + chrFeatures.size() + " features " + refVariantMap.size() + " in ref and " + testVariantMap.size() + " in test");
			
			for (Feature f : chrFeatures) {
				
				
				MutableList<VCFVariant> refVariants = refVariantMap.get(f);
				
				MutableList<VCFVariant> testVariants = testVariantMap.get(f);
				
				
				if (!(refVariantMap.get(f).size() > 0 && testVariantMap.get(f).size() > 0)) {
					// variant unique to one
					
					if (keepNonOverlapping) {
						if (refVariantMap.containsKey(f)) {
							for (int x = 0; x < refVariants.size(); x++) {
								VCFVariant var1 = refVariants.get(x);
								vcf1OutTf.writeln(var1.toVCFString());
								uniqueRef.writeln(var1.getChr() + "\t" + var1.getPos() + "\t" + var1.getId());
							}
							
						} else if (testVariantMap.containsKey(f)) {
							for (int x = 0; x < testVariants.size(); x++) {
								VCFVariant var2 = testVariants.get(x);
								vcf2OutTf.writeln(var2.toVCFString());
								uniqueTest.writeln(var2.getChr() + "\t" + var2.getPos() + "\t" + var2.getId());
							}
						}
					}
				} else {
					boolean[] testVariantAlreadyWritten = new boolean[testVariants.size()];
					
					
					for (int x = 0; x < refVariants.size(); x++) {
						VCFVariant refVariant = refVariants.get(x);
						
						boolean refVariantAlreadyMerged = false;
						
						String logln = refVariant.getChr()
								+ "\t" + refVariant.getPos()
								+ "\t" + x
								+ "\t" + refVariant.getId()
								+ "\t" + refVariant.getAlleles()[0]
								+ "\t" + Strings.concat(refVariant.getAlleles(), Strings.comma, 1, refVariant.getAlleles().length)
								+ "\t" + refVariant.getMinorAllele()
								+ "\t" + refVariant.getMAF();
						
						for (int y = 0; y < testVariants.size(); y++) {
							
							String logoutputln = logln;
							VCFVariant testVariant = testVariants.get(y);
							
							if (testVariantAlreadyWritten[y] || refVariantAlreadyMerged) {
								logoutputln += "\t" + y
										+ "\t" + testVariant.getId()
										+ "\t" + testVariant.getAlleles()[0]
										+ "\t" + Strings.concat(testVariant.getAlleles(), Strings.comma, 1, testVariant.getAlleles().length)
										+ "\t" + testVariant.getMinorAllele()
										+ "\t" + testVariant.getMAF() + "\t-\t-\tRefVariantAlreadyMerged";
								// don't write again //
							} else {
								
								
								logoutputln += "\t" + y
										+ "\t" + testVariant.getId()
										+ "\t" + testVariant.getAlleles()[0]
										+ "\t" + Strings.concat(testVariant.getAlleles(), Strings.comma, 1, testVariant.getAlleles().length)
										+ "\t" + testVariant.getMinorAllele()
										+ "\t" + testVariant.getMAF();
								
								
								if (!refVariant.getId().equals(testVariant.getId())) {
									// check whether the name is equal (this may matter for positions with multiple variants).
									logoutputln += "\t-\t-\tDifferentNames";
								} else {
									
									int nrsamples1 = refVariant.getNrSamples();
									int nrsamples2 = testVariant.getNrSamples();
									Pair<String, String> outputpair = mergeVariants(refVariant, nrsamples1, testVariant, nrsamples2, separatorInMergedFile);
									if (outputpair.getRight() != null) {
//										vcf1OutTf.writeln(refVariant.toVCFString());
//										vcf2OutTf.writeln(testVariant.toVCFString());
										mergedOut.writeln(outputpair.getRight());
										testVariantAlreadyWritten[y] = true;
										refVariantAlreadyMerged = true;
									}
									logoutputln += outputpair.getLeft();
									
								}
							}
							logOut.writeln(logoutputln);
						}
					}
				}
				
			}
			
		}
		uniqueRef.close();
		uniqueTest.close();
		
		logOut.close();
		vcf1OutTf.close();
		vcf2OutTf.close();
		mergedOut.close();
		
		
	}
	
	public int countIdenticalAlleles(String[] refAlleles, String[] testVariantAlleles) {
		int nridenticalalleles = 0;
		for (int i = 0; i < refAlleles.length; i++) {
			String allele1 = refAlleles[i];
			for (int j = 0; j < testVariantAlleles.length; j++) {
				if (testVariantAlleles[j].equals(allele1)) {
					nridenticalalleles++;
				}
			}
		}
		return nridenticalalleles;
	}
	
	/*
	Utility function to mergecheese two variants with non-overlapping samples.
	 */
	private Pair<String, String> mergeVariants(VCFVariant refVariant, int nrsamples1,
											   VCFVariant testVariant, int nrsamples2,
											   String separatorInMergedFile) {
		
		VCFFunctions t = new VCFFunctions();
		if ((refVariant == null || testVariant == null) || (refVariant.isImputed() && testVariant.isImputed())) {
			// no need to recode alleles
			String mergeStr = t.mergeVariants(refVariant, nrsamples1, testVariant, nrsamples2, separatorInMergedFile);
			
			// dont recode alleles
			if (refVariant != null && testVariant != null) {
				String[] refAlleles = refVariant.getAlleles();
				String refMinorAllele = refVariant.getMinorAllele();
				String[] testVariantAlleles = testVariant.getAlleles();
				String testVariantMinorAllele = testVariant.getMinorAllele();
				
				int nridenticalalleles = countIdenticalAlleles(refAlleles, testVariantAlleles);
				if (nridenticalalleles == refVariant.getNrAlleles() && nridenticalalleles == testVariant.getNrAlleles()) {
					String logout = "ImputedOrOneVariantNull";
					return new Pair<String, String>(logout, mergeStr);
				} else {
					String logout = "ImputedOrOneVariantNull-IncompatibleAlleles";
					return new Pair<String, String>(logout, null);
				}
			} else {
				String logout = "ImputedOrOneVariantNull";
				return new Pair<String, String>(logout, mergeStr);
			}
			
		}
		
		String[] refAlleles = refVariant.getAlleles();
		String refMinorAllele = refVariant.getMinorAllele();
		String[] testVariantAlleles = testVariant.getAlleles();
		String testVariantMinorAllele = testVariant.getMinorAllele();
		
		int nridenticalalleles = countIdenticalAlleles(refAlleles, testVariantAlleles);
		GenotypeTools gtools = new GenotypeTools();
		
		boolean complement = false;
		if (nridenticalalleles == 0) {
			// try complement
			complement = true;
			String[] complementAlleles2 = gtools.convertToComplement(testVariantAlleles);
			testVariantMinorAllele = gtools.getComplement(testVariantMinorAllele);
			if (testVariantMinorAllele == null) {
				nridenticalalleles = 0;
			} else {
				nridenticalalleles = countIdenticalAlleles(refAlleles, complementAlleles2);
			}
		}
		
		
		String logoutputln = "";
		boolean flipped = false;
		if (refVariant.getAlleles().length == 2 && testVariant.getAlleles().length == 2) {
			// simple case: both are biallelic..
			// check if the minor alleles are equal. else, skip the variant.
			if (nridenticalalleles == 2) {
				
				if (testVariantMinorAllele == null || refMinorAllele == null) {
					// meh
					System.out.println(refVariant.getId() + "\t" + refMinorAllele + "\t" + testVariantMinorAllele);
				} else if (testVariant == null) {
					System.out.println("Test variant is null for  " + refVariant.getId());
				} else if (testVariantMinorAllele.equals(refMinorAllele) || (testVariant.getMAF() > 0.45 && refVariant.getMAF() > 0.45)) {
					// check whether the reference allele is equal
					String[] tmpAlleles = testVariantAlleles;
					
					if ((refVariant.isImputed() && !testVariant.isImputed()) || (!refVariant.isImputed() && testVariant.isImputed())) {
						// one of the variants was imputed. use that as a reference in stead

//						System.out.println("Shared variant: " + refVariant.toString() + " vs " + testVariant.toString() + " is imputed ds1: "
//								+ refVariant.isImputed() + " and ds2: " + testVariant.isImputed()
//								+ "\tAlleles:" + Strings.concat(testVariant.getAlleles(), Strings.comma) + " vs " + Strings.concat(testVariant.getAlleles(), Strings.comma));
						
						VCFVariant imputedVariant = null;
						VCFVariant genotypedVariant = null;
						if (refVariant.isImputed()) {
							imputedVariant = refVariant;
							genotypedVariant = testVariant;
						} else {
							imputedVariant = testVariant;
							genotypedVariant = refVariant;
						}
						if (complement) {
							genotypedVariant.convertAllelesToComplement();
							tmpAlleles = genotypedVariant.getAlleles();
						}
						
						if (!imputedVariant.getAlleles()[0].equals(genotypedVariant.getAlleles()[0])) {
							genotypedVariant.flipReferenceAlelele();
							flipped = true;
						}
						
						logoutputln += "\t" + testVariant.getAlleles()[0] + "\t" + Strings.concat(testVariant.getAlleles(), Strings.comma, 1, testVariant.getAlleles().length);
						
						// merge
						String mergeStr = t.mergeVariants(refVariant, nrsamples1, testVariant, nrsamples2, "/");
						
						if (complement) {
							logoutputln += "\tOK-OneVarImputed-Complement";
						} else {
							logoutputln += "\tOK-OneVarImputed";
						}
						if (flipped) {
							logoutputln += "-flippedAlleles";
						}
						
						return new Pair<String, String>(logoutputln, mergeStr);
						
					} else {
						if (complement) {
							testVariant.convertAllelesToComplement();
							tmpAlleles = testVariant.getAlleles();
						}
						
						if (!refAlleles[0].equals(tmpAlleles[0])) {
							testVariant.flipReferenceAlelele();
							flipped = true;
						}
						
						logoutputln += "\t" + testVariant.getAlleles()[0] + "\t" + Strings.concat(testVariant.getAlleles(), Strings.comma, 1, testVariant.getAlleles().length);
						
						
						// mergecheese
						String mergeStr = t.mergeVariants(refVariant, nrsamples1, testVariant, nrsamples2, separatorInMergedFile);
//					mergedOut.writeln(mergeStr);
						
						if (complement) {
							logoutputln += "\tOK-Complement";
						} else {
							logoutputln += "\tOK";
						}
						if (flipped) {
							logoutputln += "-flippedAlleles";
						}
						
						return new Pair<String, String>(logoutputln, mergeStr);
					}
					
					
				} else {
					// write to log?
					logoutputln += "\t-\t-\tNotOK-DiffMinor";
					return new Pair<String, String>(logoutputln, null);
				}
			} else {
				// write to log?
				logoutputln += "\t-\t-\tNotOK-IncompatibleAlleles";
				return new Pair<String, String>(logoutputln, null);
			}
			
		} else if (nridenticalalleles > 1) {
			
			// recode the genotypes towards the joint set of alleles
			// get a list of all alleles at this locus...
			HashSet<String> uniqueAlleles = new HashSet<String>();
			uniqueAlleles.addAll(Arrays.asList(refAlleles));
			uniqueAlleles.addAll(Arrays.asList(testVariantAlleles));
			
			HashMap<String, Integer> alleleMap = new HashMap<String, Integer>();
			ArrayList<String> newAlleles = new ArrayList<String>();
			for (int i = 0; i < refAlleles.length; i++) {
				alleleMap.put(refAlleles[i], i);
				newAlleles.add(refAlleles[i]);
			}
			
			for (int i = 0; i < testVariantAlleles.length; i++) {
				String alleleStr = testVariantAlleles[i];
				if (!alleleMap.containsKey(alleleStr)) {
					alleleMap.put(alleleStr, alleleMap.size());
					newAlleles.add(alleleStr);
				}
			}
			
			
			// recode testVariant
			refVariant.recodeAlleles(alleleMap, newAlleles.toArray(new String[0]));
			testVariant.recodeAlleles(alleleMap, newAlleles.toArray(new String[0]));
			
			logoutputln += "\t" + testVariant.getAlleles()[0] + "\t" + Strings.concat(testVariant.getAlleles(), Strings.comma, 1, testVariant.getAlleles().length);
			
			// mergecheese
			String mergeStr = t.mergeVariants(refVariant, nrsamples1, testVariant, nrsamples2, separatorInMergedFile);
			logoutputln += "\tOK-MultiAllelic-AllelesRecoded";
			return new Pair<String, String>(logoutputln, mergeStr);
			
		} else {
			// variant we can't fix
			logoutputln += "\t-\t-\tNotOK-CantFix";
			return new Pair<String, String>(logoutputln, null);
		}
		return new Pair<String, String>(logoutputln, null);
	}


	/*
	For two VCF files with no overlapping samples (for now), this merges the variants.
	Overlapping variants are merged if their characteristics are similar. Otherwise they are excluded.
	Variants present in only one VCF will be given null genotypes for the samples of the other VCF
	 */
	
	public void merge(String vcf1, String vcf2, String out) throws IOException {
		
		System.out.println("in1: " + vcf1);
		System.out.println("in2: " + vcf2);
		System.out.println("out: " + out);
		
		VCFGenotypeData data1 = new VCFGenotypeData(vcf1);
		VCFGenotypeData data2 = new VCFGenotypeData(vcf2);
		
		ArrayList<String> samples1 = data1.getSamples();
		System.out.println(samples1.size() + " samples in " + vcf1);
		ArrayList<String> samples2 = data2.getSamples();
		System.out.println(samples2.size() + " samples in " + vcf2);
		
		HashMap<String, Integer> samples1Hash = new HashMap<String, Integer>();
		for (int i = 0; i < samples1.size(); i++) {
			samples1Hash.put(samples1.get(i), i);
		}
		
		ArrayList<String> sharedSamples = new ArrayList<String>();
		ArrayList<Integer> sharedSamplesIndex = new ArrayList<Integer>();
		boolean[] includeSample1 = new boolean[samples1.size()];
		for (int i = 0; i < samples2.size(); i++) {
			String sample2 = samples2.get(i);
			Integer sample1Index = samples1Hash.get(sample2);
			if (sample1Index != null) {
				sharedSamples.add(sample2);
				sharedSamplesIndex.add(sample1Index);
				includeSample1[sample1Index] = true;
			} else {
				sharedSamplesIndex.add(-1);
			}
		}
		
		System.out.println(sharedSamples.size() + " samples shared between VCFs");
		VCFVariantLoader loader = new VCFVariantLoader();
		
		
		VCFVariantFilters filters = new VCFVariantFilters();
		filters.addFilter(new VCFVariantImpQualFilter(0.3, true));
		
		if (sharedSamples.size() == 0) {
			HashMap<String, VCFVariant> variantMap = new HashMap<String, VCFVariant>();
			
			ArrayList<VCFVariant> variants1 = loader.run(vcf1, filters);
			for (VCFVariant v : variants1) {
				variantMap.put(v.toString(), v);
			}
			
			ArrayList<VCFVariant> variants2 = loader.run(vcf2, filters);
			
			System.out.println(variantMap.size() + " variants loaded from: " + vcf1);
			
			ArrayList<String> mergedSamples = new ArrayList<String>();
			mergedSamples.addAll(samples1);
			mergedSamples.addAll(samples2);
			
			TextFile outf = new TextFile(out, TextFile.W);
			
			outf.writeln("##fileformat=VCFv4.1");
			String header = "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT";
			for (int i = 0; i < samples1.size(); i++) {
				header += "\t" + samples1.get(i);
			}
			for (int i = 0; i < samples2.size(); i++) {
				header += "\t" + samples2.get(i);
			}
			outf.writeln(header);
			
			HashSet<String> vars2 = new HashSet<String>();
			int shared = 0;
			int sharedWritten = 0;
			int vcf2specific = 0;
			
			TextFile mergelog = new TextFile(out + "-mergelog.txt.gz", TextFile.W);
			ProgressBar pb = new ProgressBar(variants2.size(), "Merging variants");
			for (VCFVariant var2 : variants2) {
				VCFVariant var1 = variantMap.get(var2.toString());
				if (var1 == null) {
					String separator = "/";
					if (var2.isImputed()) {
						separator = "|";
					}
					int nrsamples1 = samples1.size();
					int nrsamples2 = samples2.size();
					Pair<String, String> outputpair = mergeVariants(var1, nrsamples1, var2, nrsamples2, separator);
					if (outputpair.getRight() != null) {
						outf.writeln(outputpair.getRight());
						sharedWritten++;
					}
					vcf2specific++;
				} else {
					// shared variant
					boolean imputed1 = var1.isImputed();
					boolean imputed2 = var2.isImputed();
					
					
					String logln = var1.getChr()
							+ "\t" + var1.getPos()
							+ "\t" + var1.getId()
							+ "\t" + var1.getAlleles()[0]
							+ "\t" + Strings.concat(var1.getAlleles(), Strings.comma, 1, var1.getAlleles().length)
							+ "\t" + var1.getMinorAllele()
							+ "\t" + var1.getMAF();
					logln += "\t" + var2.getId()
							+ "\t" + var2.getAlleles()[0]
							+ "\t" + Strings.concat(var2.getAlleles(), Strings.comma, 1, var2.getAlleles().length)
							+ "\t" + var2.getMinorAllele()
							+ "\t" + var2.getMAF();
					
					// mergecheese
					String separator = "/";
					if (imputed1 && imputed2) {
						separator = "|";
					}
					int nrsamples1 = var1.getNrSamples();
					int nrsamples2 = var2.getNrSamples();
					Pair<String, String> outputpair = mergeVariants(var1, nrsamples1, var2, nrsamples2, separator);
					String mergeStr = outputpair.getLeft();
					logln += mergeStr;
					mergelog.writeln(logln);
					if (outputpair.getRight() != null) {
						outf.writeln(outputpair.getRight());
						sharedWritten++;
					}
					shared++;
				}
				pb.iterate();
				
				vars2.add(var2.toString());
			}
			mergelog.close();
			pb.close();
			
			System.out.println(vars2.size() + " variants in: " + vcf2);
			System.out.println(vcf2specific + " specific variants in " + vcf2);
			System.out.println(shared + " shared variants");
			double percWritten = (double) sharedWritten / shared;
			System.out.println(sharedWritten + " shared variants written (" + percWritten + ")");
			
			int vcf1specific = 0;
			Set<String> keyset = variantMap.keySet();
			
			for (String s : keyset) {
				if (!vars2.contains(s)) {
					
					VCFVariant var1 = variantMap.get(s);
					
					String separator = "/";
					if (var1.isImputed()) {
						separator = "|";
					}
					
					Pair<String, String> outputpair = mergeVariants(var1, samples1.size(), null, samples2.size(), separator);
					if (outputpair.getRight() != null) {
						outf.writeln(outputpair.getRight());
						sharedWritten++;
					}
					vcf1specific++;
				}
			}
			
			System.out.println(vcf1specific + " variants specific to: " + vcf1);
			outf.close();
			
		} else {
			System.out.println(sharedSamples.size() + " shared samples detected. This method only supports unique samples for now");
		}
		
	}
	
	public void testMerge(String outf) throws IOException {
		// test file
		System.out.println();
		System.out.println("Testing output: " + outf);
		TextFile tft = new TextFile(outf, TextFile.R);
		String tfln = tft.readLine();
		int ln = 0;
		int nrSamples = 0;
		while (tfln != null) {
			if (tfln.startsWith("#")) {
				if (tfln.startsWith("#CHROM")) {
					String[] elems = tfln.split("\t");
					nrSamples = elems.length - 9;
					System.out.println(nrSamples + " samples found in header");
				}
			} else {
				String[] elems = tfln.split("\t");
				int ct2 = elems.length - 9;
				if (ct2 != nrSamples) {
					System.out.println(ct2 + " samples found " + nrSamples + " samples expected... on ln: " + ln);
				}
				VCFVariant var = new VCFVariant(tfln);
			}
			ln++;
			if (ln % 1000 == 0) {
				System.out.print("Parsed " + ln + " lines..\r");
			}
			tfln = tft.readLine();
		}
		tft.close();
		
	}
	
	public void checkImputationBatches(String dirInPrefix, String outfilename, String variantsToTestFile, int nrBatches) throws IOException {
		// make a list of variants to include
		ArrayList<String> files = new ArrayList<String>();
		HashMap<String, ArrayList<Double>> allVariants = new HashMap<String, ArrayList<Double>>();
		
		HashSet<String> varsToTest = null;
		if (variantsToTestFile != null) {
			TextFile fin = new TextFile(variantsToTestFile, TextFile.R);
			ArrayList<String> str = fin.readAsArrayList();
			boolean splitweirdly = false;
			varsToTest = new HashSet<String>();
			for (String s : str) {
				String[] elems = s.split(";");
				if (elems.length > 1) {
					varsToTest.add(elems[0] + "-" + elems[1]);
					splitweirdly = true;
				} else {
					varsToTest.add(s);
				}
				
			}
			if (splitweirdly) {
				System.out.println("split weirdly ;)");
			}
			fin.close();
			System.out.println(varsToTest.size() + " variants to mergecheese from " + variantsToTestFile);
		}
		
		System.out.println("Looking for " + nrBatches + " batches near " + dirInPrefix);
		System.out.println("Out: " + outfilename);
		
		boolean allpresent = true;
		for (int batch = 0; batch < nrBatches; batch++) {
			String batchvcfName = dirInPrefix + batch + ".vcf.gz";
			if (!Gpio.exists(batchvcfName)) {
				allpresent = false;
				System.out.println("Could not find: " + batchvcfName);
			}
		}
		
		if (!allpresent) {
			System.exit(-1);
		} else {
			System.out.println("All files are there.");
		}
		
		
		// get a list of variants and their R-squared values
		int[] variantsPerBatch = new int[nrBatches];
		for (int batch = 0; batch < nrBatches; batch++) {
			String batchvcfName = dirInPrefix + batch + ".vcf.gz";
			if (Gpio.exists(batchvcfName)) {
				files.add(batchvcfName);
				TextFile tf = new TextFile(batchvcfName, TextFile.R);
//				String[] elems = tf.readLineElems(TextFile.tab);
				String ln = tf.readLine();
				System.out.println("reading: " + batchvcfName);
				
				HashMap<String, ArrayList<Double>> allVariantsLocal = new HashMap<String, ArrayList<Double>>();
				
				while (ln != null) {
					if (ln.startsWith("#")) {
					
					} else {
						String[] elems = new String[9];
						StringTokenizer tokenizer = new StringTokenizer(ln, "\t");
						int ctr = 0;
						while (tokenizer.hasMoreTokens() && ctr < 9) {
							elems[ctr] = tokenizer.nextToken();
							ctr++;
						}
						
						// #CHROM  POS     ID      REF     ALT     QUAL    FILTER  INFO    FORMAT
						String variant = elems[0] + "_" + elems[1] + "_" + elems[2] + "_" + elems[3] + "_" + elems[4];
						String variantSelect = elems[0] + "-" + elems[1] + "-" + elems[2];
						if (varsToTest == null || varsToTest.contains(variantSelect)) {
							//	String infoStr = elems[7];
							ArrayList<Double> rsquareds = allVariants.get(variant);
							ArrayList<Double> rsquaredLocal = allVariantsLocal.get(variant);
							if (rsquareds == null) {
								rsquareds = new ArrayList<Double>();
							}
							if (rsquaredLocal == null) {
								rsquaredLocal = new ArrayList<Double>();
							}
							String[] infoElems = elems[7].split(";");
							for (String s : infoElems) {
								if (s.startsWith("AR2")) {
									String[] rsquaredElems = s.split("=");
									Double d = Double.parseDouble(rsquaredElems[1]);
									rsquareds.add(d);
									rsquaredLocal.add(d);
								}
							}
							allVariants.put(variant, rsquareds);
							allVariantsLocal.put(variant, rsquaredLocal);
						}
					}
					ln = tf.readLine();
				}
				System.out.println(allVariants.size() + " variants found... " + allVariantsLocal.size() + " in this path");
				if (allVariants.size() != allVariantsLocal.size()) {
					System.out.println("ERROR: number of variants differs! :( " + batchvcfName + " expected: " + allVariants.size() + " found " + allVariantsLocal.size());
					
				}
				tf.close();
			} else {
				System.out.println("Could not find: " + batchvcfName);
			}
		}
	}
	
	// same variants, different samples
	public void mergeImputationBatches(String dirInPrefix, String outfilename, String variantsToTestFile, int nrBatches) throws IOException {
		
		// make a list of variants to include
		ArrayList<String> files = new ArrayList<String>();
		HashMap<String, ArrayList<Double>> allVariants = new HashMap<String, ArrayList<Double>>();
		
		HashSet<String> varsToTest = null;
		if (variantsToTestFile != null) {
			TextFile fin = new TextFile(variantsToTestFile, TextFile.R);
			ArrayList<String> str = fin.readAsArrayList();
			boolean splitweirdly = false;
			varsToTest = new HashSet<String>();
			for (String s : str) {
				String[] elems = s.split(";");
				if (elems.length > 1) {
					varsToTest.add(elems[0] + "-" + elems[1]);
					splitweirdly = true;
				} else {
					varsToTest.add(s);
				}
				
			}
			if (splitweirdly) {
				System.out.println("split weirdly ;)");
			}
			fin.close();
			System.out.println(varsToTest.size() + " variants to mergecheese from " + variantsToTestFile);
		}
		
		System.out.println("Looking for " + nrBatches + " batches near " + dirInPrefix);
		System.out.println("Out: " + outfilename);
		
		boolean allpresent = true;
		for (int batch = 0; batch < nrBatches; batch++) {
			String batchvcfName = dirInPrefix + batch + ".vcf.gz";
			if (!Gpio.exists(batchvcfName)) {
				allpresent = false;
				System.out.println("Could not find: " + batchvcfName);
			}
		}
		
		if (!allpresent) {
			System.exit(-1);
		} else {
			System.out.println("All files are there.");
		}
		
		
		// get a list of variants and their R-squared values
		for (int batch = 0; batch < nrBatches; batch++) {
			String batchvcfName = dirInPrefix + batch + ".vcf.gz";
			if (Gpio.exists(batchvcfName)) {
				files.add(batchvcfName);
				TextFile tf = new TextFile(batchvcfName, TextFile.R);
//				String[] elems = tf.readLineElems(TextFile.tab);
				String ln = tf.readLine();
				System.out.println("reading: " + batchvcfName);
				
				HashMap<String, ArrayList<Double>> allVariantsLocal = new HashMap<String, ArrayList<Double>>();
				
				while (ln != null) {
					if (ln.startsWith("#")) {
					
					} else {
						
						String substr = ln.substring(0, 1000);
						String[] elems = substr.split("\t");
						
						// #CHROM  POS     ID      REF     ALT     QUAL    FILTER  INFO    FORMAT
						String variant = elems[0] + "_" + elems[1] + "_" + elems[2] + "_" + elems[3] + "_" + elems[4];
						String variantSelect = elems[0] + "-" + elems[1] + "-" + elems[2];
						if (varsToTest == null || varsToTest.contains(variantSelect)) {
							//	String infoStr = elems[7];
							ArrayList<Double> rsquareds = allVariants.get(variant);
							ArrayList<Double> rsquaredLocal = allVariantsLocal.get(variant);
							if (rsquareds == null) {
								rsquareds = new ArrayList<Double>();
							}
							if (rsquaredLocal == null) {
								rsquaredLocal = new ArrayList<Double>();
							}
							String[] infoElems = elems[7].split(";");
							for (String s : infoElems) {
								if (s.startsWith("AR2") || s.startsWith("R2") || s.startsWith("INFO")) {
									String[] rsquaredElems = s.split("=");
									Double d = Double.parseDouble(rsquaredElems[1]);
									rsquareds.add(d);
									rsquaredLocal.add(d);
								}
							}
							allVariants.put(variant, rsquareds);
							allVariantsLocal.put(variant, rsquaredLocal);
						}
					}
					ln = tf.readLine();
				}
				System.out.println(allVariants.size() + " variants found... " + allVariantsLocal.size() + " in this path");
				if (allVariants.size() != allVariantsLocal.size()) {
					System.out.println("ERROR: number of variants differs! :( ");
					System.exit(-1);
				}
				tf.close();
			} else {
				System.out.println("Could not find: " + batchvcfName);
			}
		}
		
		if (files.size() != nrBatches) {
			System.err.println("Batches missing for " + dirInPrefix);
		} else {
			System.out.println("great. all files are here :)");
			// remap variants
			Set<String> variants = allVariants.keySet();
			
			HashMap<String, Integer> variantToInt = new HashMap<String, Integer>();
			int ctr = 0;
			HashMap<String, Double> variantToAR2 = new HashMap<String, Double>();
			for (String s : variants) {
				ArrayList<Double> d = allVariants.get(s);
				if (d.size() == nrBatches) {
					variantToInt.put(s, ctr);
					double[] doubleArr = Primitives.toPrimitiveArr(d.toArray(new Double[0]));
					double medianar2 = JSci.maths.ArrayMath.median(doubleArr);
					variantToAR2.put(s, medianar2);
					ctr++;
				} else {
					System.out.println(s + " may have duplicates? " + d.size() + " variants found, expected: " + nrBatches);
					System.exit(-1);
				}
			}
			
			System.out.println(variantToInt.size() + " variants in total");
			
			
			VCFVariant[][] variantObjs = new VCFVariant[nrBatches][variantToInt.size()];
			
			StringBuilder header = new StringBuilder();
			header.append("#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT");
			
			for (int batch = 0; batch < nrBatches; batch++) {
				String batchvcfName = dirInPrefix + batch + ".vcf.gz";
				TextFile tf = new TextFile(batchvcfName, TextFile.R);
				
				System.out.println("reading: " + batchvcfName);
				String ln = tf.readLine();
				while (ln != null) {
					if (ln.startsWith("##")) {
					
					} else if (ln.startsWith("#CHROM")) {
						String[] elems = ln.split("\t");
						for (int i = 9; i < elems.length; i++) {
							header.append("\t").append(elems[i]);
						}
						
					} else {
						// #CHROM  POS     ID      REF     ALT     QUAL    FILTER  INFO    FORMAT
						String substr = ln.substring(0, 200);
						String[] elems = substr.split("\t");
						String variant = elems[0] + "_" + elems[1] + "_" + elems[2] + "_" + elems[3] + "_" + elems[4];
						
						Integer id = variantToInt.get(variant);
						if (id != null) {
							VCFVariant variantObj = new VCFVariant(ln);
							variantObjs[batch][id] = variantObj;
						} else {
							System.out.println("variant: " + variant + " not in index??");
							System.exit(-1);
						}
					}
					ln = tf.readLine();
				}
				tf.close();
			}
			
			System.out.println("done reading. writing to: " + outfilename);
			TextFile vcfout = new TextFile(outfilename, TextFile.W);
			
			vcfout.writeln("##fileformat=VCFv4.1");
			vcfout.writeln(header.toString());
			for (int i = 0; i < variantToInt.size(); i++) {
				StringBuilder builder = new StringBuilder(100000);
				for (int b = 0; b < nrBatches; b++) {
					VCFVariant variant = variantObjs[b][i];
					if (b == 0) {
						builder.append(variant.toVCFString(true));
					} else {
						builder.append("\t").append(variant.toVCFString(false));
					}
				}
				vcfout.writeln(builder.toString());
				if (i % 100 == 0) {
					System.out.print("\rprogress: " + i + "/" + variantToInt.size() + " - " + ((double) i / variantToInt.size()) + "\r");
				}
			}
			System.out.println("Done writing");
			vcfout.close();
			System.out.println();
			
			
			System.out.println("Testing output path: " + outfilename);
			TextFile tfin = new TextFile(outfilename, TextFile.R);
			String[] elems = tfin.readLineElems(TextFile.tab);
			int nr = -1;
			int ln = 0;
			while (elems != null) {
				if (elems[0].startsWith("##")) {
				
				} else if (elems[0].startsWith("#")) {
					System.out.println(elems.length + " header elems");
					if (nr == -1) {
						nr = elems.length;
					}
				} else {
					if (nr == -1) {
						System.out.println(elems.length + " sample elems: " + (elems.length - 9) + " samples");
						nr = elems.length;
					} else {
						if (nr != elems.length) {
							System.err.println("error detected in output on line " + ln + " " + elems.length + " found " + nr + " expected ");
						}
					}
				}
				ln++;
				elems = tfin.readLineElems(TextFile.tab);
			}
			tfin.close();
			
		}
		
	}
	
	// same variants, different samples
	public void mergeImputedData(String vcf1, String vcf2, String outfilename, String variantsToTestFile) throws IOException {
		
		// make a list of variants to include
		System.out.println("VCF1: " + vcf1);
		System.out.println("VCF2: " + vcf2);
		System.out.println("Out: " + outfilename);
		
		boolean allpresent = true;
		if (!Gpio.exists(vcf1) || !Gpio.exists(vcf2)) {
			System.out.println("Could not find one of the input files");
			System.exit(-1);
		}
		
		HashSet<String> varsToTest = null;
		if (variantsToTestFile != null) {
			TextFile fin = new TextFile(variantsToTestFile, TextFile.R);
			ArrayList<String> str = fin.readAsArrayList();
			boolean splitweirdly = false;
			varsToTest = new HashSet<String>();
			for (String s : str) {
				String[] elems = s.split(";");
				if (elems.length > 1) {
					varsToTest.add(elems[0] + "-" + elems[1]);
					splitweirdly = true;
				} else {
					varsToTest.add(s);
				}
				
			}
			if (splitweirdly) {
				System.out.println("split weirdly ;)");
			}
			fin.close();
			System.out.println(varsToTest.size() + " variants to mergecheese from " + variantsToTestFile);
		}
		
		HashSet<String> allVariants = new HashSet<String>();
		// get a list of variants and their R-squared values
		String[] files = new String[]{vcf1, vcf2};
		for (int f = 0; f < files.length; f++) {
			String vcfname = files[f];
			TextFile tf = new TextFile(vcfname, TextFile.R);
			
			String ln = tf.readLine();
			System.out.println("reading: " + vcfname);
			
			
			int ctr = 0;
			while (ln != null) {
				if (ln.startsWith("#")) {
				
				} else {
					
					String substr = ln.substring(0, 1000);
					String[] elems = substr.split("\t");
					
					// #CHROM  POS     ID      REF     ALT     QUAL    FILTER  INFO    FORMAT
					String variant = elems[0] + "_" + elems[1] + "_" + elems[2] + "_" + elems[3] + "_" + elems[4];
					String variantSelect = elems[0] + "-" + elems[1] + "-" + elems[2];
					if (varsToTest == null || varsToTest.contains(variantSelect)) {
						//	String infoStr = elems[7];
						allVariants.add(variant);
					}
					ctr++;
					if (ctr % 1000 == 0) {
						System.out.print(ctr + " lines parsed.\r");
					}
					
				}
				ln = tf.readLine();
			}
			System.out.println(allVariants.size() + " variants found... ");
			tf.close();
			System.out.println();
		}
		
		// remap variants
		Set<String> variants = allVariants;
		HashMap<String, Integer> variantToInt = new HashMap<String, Integer>();
		int ctr = 0;
		for (String s : variants) {
			variantToInt.put(s, ctr);
			ctr++;
		}
		
		System.out.println(variantToInt.size() + " variants in total");
		VCFVariant[][] variantObjs = new VCFVariant[2][variantToInt.size()];
		
		StringBuilder header = new StringBuilder();
		header.append("#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT");
		
		int[] nrSamples = new int[2];
		
		for (int f = 0; f < files.length; f++) {
			String vcfname = files[f];
			VCFGenotypeData d = new VCFGenotypeData(vcfname);
			nrSamples[f] = d.getSamples().size();
			TextFile tf = new TextFile(vcfname, TextFile.R);
			
			System.out.println("reading: " + vcfname);
			String ln = tf.readLine();
			int lnctr = 0;
			int varctr = 0;
			while (ln != null) {
				if (ln.startsWith("##")) {
				
				} else if (ln.startsWith("#CHROM")) {
					String[] elems = ln.split("\t");
					for (int i = 9; i < elems.length; i++) {
						header.append("\t").append(elems[i]);
					}
					
				} else if (ln.startsWith("#")) {
				
				} else {
					// #CHROM  POS     ID      REF     ALT     QUAL    FILTER  INFO    FORMAT
					String substr = ln.substring(0, 200);
					String[] elems = substr.split("\t");
					String variant = elems[0] + "_" + elems[1] + "_" + elems[2] + "_" + elems[3] + "_" + elems[4];
					
					Integer id = variantToInt.get(variant);
					if (id != null) {
						VCFVariant variantObj = new VCFVariant(ln);
						variantObjs[f][id] = variantObj;
						varctr++;
					} else {
						System.out.println("variant: " + variant + " not in index??");
						System.exit(-1);
					}
					lnctr++;
					if (lnctr % 1000 == 0) {
						System.out.print(lnctr + " lines parsed. " + ((double) varctr / variantToInt.size()) + " % of variants loaded. \r");
					}
				}
				ln = tf.readLine();
			}
			System.out.print(lnctr + " lines parsed. " + ((double) varctr / variantToInt.size()) + " % of variants loaded. \r\n");
			tf.close();
		}
		
		
		System.out.println("done reading. writing to: " + outfilename);
		TextFile vcfout = new TextFile(outfilename, TextFile.W);
		
		vcfout.writeln("##fileformat=VCFv4.1");
		vcfout.writeln(header.toString());
		for (int i = 0; i < variantToInt.size(); i++) {
			StringBuilder builder = new StringBuilder(100000);
			for (int b = 0; b < 2; b++) {
				VCFVariant variant = variantObjs[b][i];
				if (variant == null) {
					if (b == 0) {
						// get variant from b == 1 for purpose of headerness
						VCFVariant other = variantObjs[1][i];
						builder.append(other.toVCFHeader());
					}
					// append ./. to output for nrSamples
					builder.append(Strings.repeat("\t.|.", nrSamples[b]));
				}
				if (b == 0) {
					builder.append(variant.toVCFString(true));
				} else {
					builder.append("\t").append(variant.toVCFString(false));
				}
			}
			vcfout.writeln(builder.toString());
			if (i % 100 == 0) {
				System.out.print("progress: " + i + "/" + variantToInt.size() + " - " + ((double) i / variantToInt.size()) + "\r");
			}
		}
		System.out.println("Done writing");
		vcfout.close();
		System.out.println();
		
		
		System.out.println("Testing output path: " + outfilename);
		TextFile tfin = new TextFile(outfilename, TextFile.R);
		String[] elems = tfin.readLineElems(TextFile.tab);
		int nr = -1;
		int ln = 0;
		while (elems != null) {
			if (elems[0].startsWith("##")) {
			
			} else if (elems[0].startsWith("#")) {
				System.out.println(elems.length + " header elems");
				if (nr == -1) {
					nr = elems.length;
				}
			} else {
				if (nr == -1) {
					System.out.println(elems.length + " sample elems: " + (elems.length - 9) + " samples");
					nr = elems.length;
				} else {
					if (nr != elems.length) {
						System.err.println("error detected in output on line " + ln + " " + elems.length + " found " + nr + " expected ");
					}
				}
			}
			ln++;
			elems = tfin.readLineElems(TextFile.tab);
		}
		tfin.close();
		
		
	}
	
	
	public void reintroducteNonImputedVariants(String imputedVCF, String unimputedVCF, String outfilename,
											   boolean linux, String vcfsort) throws IOException {
		
		
		// get list of imputed variants
		VCFGenotypeData dataset1 = new VCFGenotypeData(imputedVCF);
		ArrayList<String> samplesImputed = dataset1.getSamples();
		
		VCFGenotypeData dataset2 = new VCFGenotypeData(unimputedVCF);
		ArrayList<String> samplesNonImputed = dataset2.getSamples();
		
		// reorder samples in unimputed path
		HashMap<String, Integer> sampleIndex = new HashMap<String, Integer>();
		for (int i = 0; i < samplesImputed.size(); i++) {
			sampleIndex.put(samplesImputed.get(i), i);
		}
		
		int[] sampleIndexArr = new int[samplesNonImputed.size()];
		for (int i = 0; i < sampleIndexArr.length; i++) {
			sampleIndexArr[i] = -1;
		}
		
		int shared = 0;
		for (int i = 0; i < samplesNonImputed.size(); i++) {
			String sample = samplesNonImputed.get(i);
			Integer index = sampleIndex.get(sample);
			if (index != null) {
				sampleIndexArr[i] = index;
				shared++;
			}
		}
		
		System.out.println(samplesImputed.size() + " samples in: " + imputedVCF);
		System.out.println(samplesNonImputed.size() + " samples in: " + unimputedVCF);
		System.out.println(shared + " samples shared.");
		
		dataset1.close();
		dataset2.close();
		
		// get list of variants unique to non-imputed list
		TextFile outfile = new TextFile(outfilename, TextFile.W);
		
		TextFile vcf1 = new TextFile(imputedVCF, TextFile.R);
		String ln = vcf1.readLine();
		HashSet<String> variantsImputed = new HashSet<String>();
		while (ln != null) {
			String[] elems = Strings.tab.split(ln);
			String variant = elems[0] + "_" + elems[1] + "_" + elems[2];
			variantsImputed.add(variant);
			outfile.writeln(ln);
		}
		vcf1.close();
		
		TextFile vcf2 = new TextFile(unimputedVCF, TextFile.R);
		String[] elems = vcf2.readLineElems(TextFile.tab);
		while (elems != null) {
			if (elems.length > 0 && elems[0].startsWith("#")) {
				String variant = elems[0] + "_" + elems[1] + "_" + elems[2];
				if (!variantsImputed.contains(variant)) {
					String lnout = Strings.concat(elems, Strings.tab, 0, 9);
					String[] outElems = new String[samplesImputed.size()];
					for (int i = 9; i < elems.length; i++) {
						int index = sampleIndexArr[i - 9];
						if (index != -1) {
							outElems[index] = elems[i];
						}
					}
					lnout += "\t" + Strings.concat(outElems, Strings.tab);
					outfile.writeln(lnout);
				}
			}
			elems = vcf2.readLineElems(TextFile.tab);
		}
		vcf2.close();
		
		outfile.close();
		
		// sort the output
		VCFFunctions func = new VCFFunctions();
		func.sortVCF(linux, vcfsort, outfilename, outfilename + "-sorted.vcf.gz", outfilename + "-sorting.sh");
		Gpio.delete(outfilename + "-sorting.sh");
		Gpio.moveFile(outfilename + "-sorted.vcf.gz", outfilename);
		
		
	}
}
