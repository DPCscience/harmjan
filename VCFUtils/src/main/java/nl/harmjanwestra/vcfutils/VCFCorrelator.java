package nl.harmjanwestra.vcfutils;

import cern.colt.matrix.tdouble.DoubleMatrix2D;
import nl.harmjanwestra.utilities.enums.Chromosome;
import nl.harmjanwestra.utilities.vcf.VCFGenotypeData;
import nl.harmjanwestra.utilities.vcf.VCFImputationQualScoreBeagle;
import nl.harmjanwestra.utilities.vcf.VCFImputationQualScoreImpute;
import nl.harmjanwestra.utilities.vcf.VCFVariant;
import nl.harmjanwestra.utilities.legacy.genetica.containers.Pair;
import nl.harmjanwestra.utilities.legacy.genetica.io.text.TextFile;
import nl.harmjanwestra.utilities.legacy.genetica.math.stats.Regression;
import nl.harmjanwestra.utilities.legacy.genetica.text.Strings;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.*;

/**
 * Created by hwestra on 12/8/15.
 */
public class VCFCorrelator {

	private boolean calculateMAFOverAllSamples = false;

	public static void main(String[] args) {
//		VCFCorrelator c = new VCFCorrelator();
//		try {
////			c.updateVCFInfoScore("/Data/tmp/2016-06-10/test.vcf", "/Data/tmp/2016-06-10/testo.vcf", true);
//		} catch (IOException e) {
//			e.printStackTrace();
//		}
	}

	public void updateVCFInfoScore(String vcfin, String vcfOut, Integer threads, boolean infoscore, boolean beaglescore) throws IOException {
		System.out.println("Will replace imputation quals.");
		System.out.println("In: " + vcfin);
		System.out.println("Out: " + vcfOut);
		if (infoscore) {
			System.out.println("Using info score");
		} else if (beaglescore) {
			System.out.println("Using beagle imputation AR2/DR2");
		} else {
			System.out.println("Will correlate dosages against best genotype predictions");
		}

		TextFile tf = new TextFile(vcfin, TextFile.R);
		TextFile out = new TextFile(new File(vcfOut), TextFile.W);
		String ln = tf.readLine();
		int submitted = 0;

		int cores = Runtime.getRuntime().availableProcessors();
		System.out.println("Detected " + cores + " Processors ");
		if (threads != null) {
			if (threads < 1) {
				threads = 1;
			}
		} else {
			threads = cores;
		}
		ExecutorService threadPool = Executors.newFixedThreadPool(threads);
		CompletionService<String[]> jobHandler = new ExecutorCompletionService<>(threadPool);

		int nrRead = 0;

		int buffersize = 25;
		String[] buffer = new String[buffersize];
		int lnctr = 0;
		while (ln != null) {
			if (ln.startsWith("#")) {
				out.writeln(ln);
			} else {

				buffer[lnctr] = ln;
				lnctr++;
				if (lnctr == buffersize) {
					VCFVariantInfoUpdater task = new VCFVariantInfoUpdater(buffer, infoscore, beaglescore);
					jobHandler.submit(task);
					buffer = new String[buffersize];
					lnctr = 0;
					submitted++;
				}

				nrRead++;
				if (submitted % threads == 0) {
					int returned = 0;
					while (returned < submitted) {
						Future<String[]> future = null;
						try {
							future = jobHandler.take();
							if (future != null) {
								String[] outStr = future.get();
								for (int q = 0; q < outStr.length; q++) {
									if (outStr[q] != null) {
										out.writeln(outStr[q]);
									}
								}
								returned++;
							}
						} catch (InterruptedException e) {
							e.printStackTrace();
						} catch (ExecutionException e) {
							e.printStackTrace();
						}
					}
					submitted = 0;
					System.out.print(nrRead + " variants processed\r");
				}
			}
			ln = tf.readLine();
		}
		System.out.println("Done reading");

		int returned = 0;
		while (returned < submitted) {
			Future<String[]> future = null;
			try {
				future = jobHandler.take();
				if (future != null) {
					String[] outStr = future.get();
					for (int q = 0; q < outStr.length; q++) {
						if (outStr[q] != null) {
							out.writeln(outStr[q]);
						}
					}
					returned++;
				}
			} catch (InterruptedException e) {
				e.printStackTrace();
			} catch (ExecutionException e) {
				e.printStackTrace();
			}
		}
		submitted = 0;

		System.out.println(nrRead + " variants total.");
		out.close();
		tf.close();
		threadPool.shutdown();
	}

	public void run(String vcf1, String vcf2, String variantsToTestFile, String out, boolean writemissingvariants) throws IOException {

		// get samples in vcf1 and 2
		// index samples
		// load list of variants (if any)
		// load variants in one dataset
		// iterate the other dataset
		// correlate

		System.out.println("Output here: " + out);

		VCFGenotypeData data1 = new VCFGenotypeData(vcf1);
		VCFGenotypeData data2 = new VCFGenotypeData(vcf2);

		ArrayList<String> samples1Initial = data1.getSamples();
		System.out.println(samples1Initial.size() + " samples in " + vcf1);
		ArrayList<String> samples2Initial = data2.getSamples();
		System.out.println(samples2Initial.size() + " samples in " + vcf2);
		data1.close();
		data2.close();

		HashSet<String> samples1Hash = new HashSet<String>();
		for (int i = 0; i < samples1Initial.size(); i++) {
			samples1Hash.add(samples1Initial.get(i));
		}

		ArrayList<String> sharedSamples = new ArrayList<String>();
		HashMap<String, Integer> sharedSamplesIndex = new HashMap<String, Integer>();

		int sctr = 0;
		for (String s : samples2Initial) {
			if (samples1Hash.contains(s)) {
				sharedSamples.add(s);
				sharedSamplesIndex.put(s, sctr);
				sctr++;
			}
		}
		System.out.println(sharedSamples.size() + " samples shared between VCFs");


		ArrayList<String> samples1 = new ArrayList<>();
		boolean[] includeSamples1 = new boolean[samples1Initial.size()];
		for (int s1 = 0; s1 < samples1Initial.size(); s1++) {
			if (sharedSamplesIndex.containsKey(samples1Initial.get(s1))) {
				includeSamples1[s1] = true;
				samples1.add(samples1Initial.get(s1));
			}
		}

		ArrayList<String> samples2 = new ArrayList<>();
		boolean[] includeSamples2 = new boolean[samples2Initial.size()];
		for (int s2 = 0; s2 < samples2Initial.size(); s2++) {
			if (sharedSamplesIndex.containsKey(samples2Initial.get(s2))) {
				includeSamples2[s2] = true;
				samples2.add(samples2Initial.get(s2));
			}
		}


		TextFile sharedsampleout = new TextFile(out + "-sharedsamples.txt", TextFile.W);
		for (String sample : sharedSamples) {
			sharedsampleout.writeln(sample);
		}
		sharedsampleout.close();

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
			System.out.println(varsToTest.size() + " variants to test from " + variantsToTestFile);
		}

		HashMap<String, VCFVariant> variantMap = new HashMap<String, VCFVariant>();
		ArrayList<VCFVariant> variants1 = new ArrayList<VCFVariant>();
		int ctr1 = 0;

		TextFile tfvcf1 = new TextFile(vcf1, TextFile.R);
		String vcf1ln = tfvcf1.readLine();
		while (vcf1ln != null) {
			if (!vcf1ln.startsWith("#")) {
				VCFVariant var = null;
				if (calculateMAFOverAllSamples) {
					var = new VCFVariant(vcf1ln, VCFVariant.PARSE.ALL);
				} else {
					var = new VCFVariant(vcf1ln, VCFVariant.PARSE.ALL, includeSamples1);
				}
				Chromosome chr = Chromosome.parseChr(var.getChr());

				if (!chr.equals(Chromosome.X)) {
					String varStr = var.toString();
					if (varsToTest == null || varsToTest.contains(varStr)) {
						variantMap.put(var.toString(), var);
						variants1.add(var);
					}
				}
				ctr1++;
				if (ctr1 % 1000 == 0) {
					System.out.println(ctr1 + " variants parsed from vcf1");
				}
			}

			vcf1ln = tfvcf1.readLine();
		}
		tfvcf1.close();

		System.out.println(variantMap.size() + " variants loaded from " + vcf1);

		TextFile tfot = new TextFile(out, TextFile.W);
		HashMap<String, VCFVariant> variantMap2 = new HashMap<>();
		HashSet<String> writtenVariants = new HashSet<String>();
		int ctr2 = 0;
		TextFile tfVCF2 = new TextFile(vcf2, TextFile.R);
		String ln = tfVCF2.readLine();
		while (ln != null) {
			if (!ln.startsWith("#")) {

				int strlen = ln.length();
				int substrlen = 500;
				if (strlen < substrlen) {
					substrlen = strlen;
				}
				String lnheader = ln.substring(0, substrlen);
				String[] lnheaderelems = lnheader.split("\t");

				// return this.chr + "_" + this.pos + "_" + this.id;
				String varStr = lnheaderelems[0] + "_" + lnheaderelems[1] + "_" + lnheaderelems[2];

				VCFVariant var1 = variantMap.get(varStr);
				if (var1 != null) {
					VCFVariant var2 = null;
					if (calculateMAFOverAllSamples) {
						var2 = new VCFVariant(ln, VCFVariant.PARSE.ALL);
					} else {
						var2 = new VCFVariant(ln, VCFVariant.PARSE.ALL, includeSamples2);
					}
//					if (var2.getTokens() != null) {
//						var2.parseGenotypes(var2.getTokens(), VCFVariant.PARSE.ALL);
//						var2.cleartokens();
//					} else {
//						System.out.println(var2.toString());
//					}

					double[][] gprobs1 = var1.getDosage();


					double[][] gprobs2 = var2.getDosage(); // format [samples][alleles]


					// check if variants have the same number of alleles
					if (gprobs1[0].length == gprobs2[0].length) {

						// recode: make sure sample ordering is identical
						gprobs1 = reorder(gprobs1, samples1, sharedSamplesIndex, sharedSamples.size());
//						System.out.println("x1:" + gprobs1.length + "x" + gprobs1[0].length);

						gprobs2 = reorder(gprobs2, samples2, sharedSamplesIndex, sharedSamples.size());
//						System.out.println("x2:" + gprobs2.length + "x" + gprobs2[0].length);

						// remove missing genotypes
						Pair<double[][], double[][]> data = removeNulls(gprobs1, gprobs2);

						for (int a = 0; a < gprobs1[0].length; a++) {
							// do some remapping here
							double[] x = toArr(data.getLeft(), a);
							double[] y = toArr(data.getRight(), a);

							if (x.length > 0 && y.length > 0) {
								double r = JSci.maths.ArrayMath.correlation(x, y);

								// calculateWithinDataset betas

								double[] coeff = Regression.getLinearRegressionCoefficients(x, y);
								double beta = coeff[0];
								double se = coeff[2];

								var1.recalculateMAFAndCallRate();
								var2.recalculateMAFAndCallRate();

								String var1Str = var1.getMinorAllele() + "\t" + Strings.concat(var1.getAlleles(), Strings.comma) + "\t" + var1.getMAF() + "\t" + var1.getCallrate();
								String var2Str = var2.getMinorAllele() + "\t" + Strings.concat(var2.getAlleles(), Strings.comma) + "\t" + var2.getMAF() + "\t" + var2.getCallrate();

								if (Double.isNaN(r)) {
									ln = var1.toString() + "\t" + var1Str + "\t" + var2Str + "\t" + (a + 1) + "\t" + data.getLeft().length + "\t" + 0 + "\t" + 0 + "\t" + 0 + "\t" + 0 + "\t" + var1.getImputationQualityScore() + "\t" + var2.getImputationQualityScore();
									System.out.println("NaN correlation?");
								} else {
									double rsq = r * r;
									ln = var1.toString() + "\t" + var1Str + "\t" + var2Str + "\t" + (a + 1) + "\t" + data.getLeft().length + "\t" + r + "\t" + rsq + "\t" + beta + "\t" + se + "\t" + var1.getImputationQualityScore() + "\t" + var2.getImputationQualityScore();
								}
								tfot.writeln(ln);
								writtenVariants.add(varStr);
							} else {
								System.out.println("length == 0: i1:" + x.length + ", i2: " + y.length);
							}

						}
					} else {
						// ?
						System.out.println("Unequal lengths? " + gprobs1[0].length + "\t" + gprobs2[0].length);
					}
				} else {
					if (varsToTest != null && varsToTest.contains(varStr)) {
						VCFVariant var2 = new VCFVariant(ln);
						variantMap2.put(varStr, var2);
					}
				}
				ctr2++;
				if (ctr2 % 1000 == 0) {
					System.out.print(ctr2 + " variants parsed from vcf2\r");
				}
			}
			ln = tfVCF2.readLine();
		}
		tfVCF2.close();
		System.out.println();

		// write variants that are not in variantlist
		if (varsToTest != null) {
			for (String variant : varsToTest) {

				if (!writtenVariants.contains(variant)) {
					VCFVariant var1 = variantMap.get(variant);
					VCFVariant var2 = variantMap2.get(variant);

					if ((var1 == null || var2 == null) || (var1 == null && var2 == null)) {

						String var1Str = "";
						String var2Str = "";
						String infoStr1 = null;
						String infoStr2 = null;
						String varstr = variant;


						if (var1 == null && var2 == null) {
							var1Str = null + "\t" + null + "\t" + 0 + "\t" + 0;
							var2Str = null + "\t" + null + "\t" + 0 + "\t" + 0;
						} else if (var1 != null && var2 == null) {
							varstr = var1.toString();
							infoStr1 = "" + var1.getInfo().get("AR2");
							var1Str = var1.getMinorAllele() + "\t" + Strings.concat(var1.getAlleles(), Strings.comma) + "\t" + var1.getMAF() + "\t" + var1.getCallrate();
							var2Str = null + "\t" + null + "\t" + 0;
						} else if (var1 == null && var2 != null) {
							varstr = var2.toString();
							infoStr2 = "" + var2.getInfo().get("AR2");
							var1Str = null + "\t" + null + "\t" + 0;
							var2Str = var2.getMinorAllele() + "\t" + Strings.concat(var2.getAlleles(), Strings.comma) + "\t" + var2.getMAF() + "\t" + var2.getCallrate();
						}
						ln = varstr + "\t" + var1Str + "\t" + var2Str + "\t" + 0 + "\t" + 0 + "\t" + 0 + "\t" + 0 + "\t" + infoStr1 + "\t" + infoStr2;
						tfot.writeln(ln);
					}
				}

			}
		} else if (writemissingvariants) {
			for (VCFVariant var1 : variants1) {
				String varstr = var1.toString();
				if (!writtenVariants.contains(varstr)) {
					String var1Str = "";
					String var2Str = "";
					String infoStr1 = null;
					String infoStr2 = null;

					varstr = var1.toString();
					infoStr1 = "" + var1.getInfo().get("AR2");
					var1Str = var1.getMinorAllele() + "\t" + Strings.concat(var1.getAlleles(), Strings.comma) + "\t" + var1.getMAF() + "\t" + var1.getCallrate();
					var2Str = null + "\t" + null + "\t" + 0;

					ln = varstr + "\t" + var1Str + "\t" + var2Str + "\t" + 0 + "\t" + 0 + "\t" + 0 + "\t" + 0 + "\t" + infoStr1 + "\t" + infoStr2;
					tfot.writeln(ln);
				}
			}
		}

		tfot.close();
	}

	private Pair<double[][], double[][]> removeNulls(double[][] gprobs1, double[][] gprobs2) {

		int nrNull = 0;
		for (int i = 0; i < gprobs1.length; i++) {
			double d = gprobs1[i][0];
			double d2 = gprobs2[i][0];
//			System.out.println(i + "\t" + d + "\t" + d2);
			if (Double.isNaN(d) || Double.isNaN(d2) || d == -1 || d2 == -1) {
				nrNull++;
			}
		}

		double[][] out1 = new double[gprobs1.length - nrNull][];
		double[][] out2 = new double[gprobs1.length - nrNull][];
		int ctr = 0;
		for (int i = 0; i < gprobs1.length; i++) {
			double d = gprobs1[i][0];
			double d2 = gprobs2[i][0];
			if (!Double.isNaN(d) && !Double.isNaN(d2) && d != -1 && d2 != -1) {
				out1[ctr] = gprobs1[i];
				out2[ctr] = gprobs2[i];
				ctr++;
			}
		}

		return new Pair<double[][], double[][]>(out1, out2);
	}

	private double[][] reorder(double[][] gprobs2, ArrayList<String> samples, HashMap<String, Integer> sharedSamplesIndex, int size) {
		double[][] output = new double[size][gprobs2[0].length];

		// initialize with NaNs
		for (int i = 0; i < output.length; i++) {
			for (int j = 0; j < output[i].length; j++) {
				output[i][j] = Double.NaN;
			}
		}

		for (int i = 0; i < gprobs2.length; i++) {
			String sample = samples.get(i);
			Integer index = sharedSamplesIndex.get(sample);
			if (index != null) {
				for (int j = 0; j < gprobs2[i].length; j++) {
					output[index][j] = gprobs2[i][j];
				}
			}
		}
		return output;
	}

	private double[][] recode1(double[][] gprobs1, boolean[] includeSample1, int size) {
		double[][] output = new double[size][gprobs1[0].length];
		int ctr = 0;
		for (int i = 0; i < gprobs1.length; i++) {
			if (includeSample1[i]) {
				for (int j = 0; j < gprobs1[i].length; j++) {
					output[ctr][j] = gprobs1[i][j];
				}
				ctr++;
			}
		}
		return output;
	}

	private double[] toArr(double[][] x, int q) {
		double[] arr = new double[x.length];
		for (int i = 0; i < arr.length; i++) {
			arr[i] = x[i][q];
		}
		return arr;
	}

	public class VCFVariantInfoUpdater implements Callable<String[]> {

		private final boolean beaglescore;
		private String[] in = null;
		private boolean infoscore = false;

		public VCFVariantInfoUpdater(String[] in, boolean infoscore, boolean beaglescore) {
			this.in = in;
			this.infoscore = infoscore;
			this.beaglescore = beaglescore;
		}

		@Override
		public String[] call() throws Exception {

			String[] output = new String[in.length];
			for (int v = 0; v < in.length; v++) {
				String ln = in[v];
				if (ln != null) {
					VCFVariant variant = new VCFVariant(ln);

					if (!variant.isImputed() || variant.getGenotypeProbabilies() == null) {
						output[v] = ln;
					} else {
						int nrAlleles = variant.getAlleles().length;
						double rsq = 0;
						String[] elems = Strings.tab.split(ln);
						if (nrAlleles == 2) {
							if (infoscore) {
								VCFImputationQualScoreImpute q = new VCFImputationQualScoreImpute();
								q.computeAutosomal(variant);
								rsq = q.getImpinfo();
								elems[7] = "INFO=" + rsq;
							} else if (beaglescore) {
								VCFImputationQualScoreBeagle b = new VCFImputationQualScoreBeagle(variant, true);
							} else {
								double[] dosageArr = convertToProbsToDouble(variant);
								double[] bestguess = convertGenotypesToDouble(variant);
								double r = JSci.maths.ArrayMath.correlation(bestguess, dosageArr);
								rsq = r * r;
								elems[7] = "INFO=" + rsq;
							}
						} else {
							VCFImputationQualScoreBeagle q = new VCFImputationQualScoreBeagle(variant, true);
							rsq = q.doseR2();
							double ar2 = q.allelicR2();
							double dr2 = q.doseR2();
							elems[7] = "AR2=" + ar2 + ";DR2=" + dr2;
						}

						output[v] = Strings.concat(elems, Strings.tab);
					}
				}
			}
			return output;
		}

		private double[] convertToProbsToDouble(VCFVariant vcfVariant) {
			double[][] dosages = vcfVariant.getDosage(); // [samples][alleles];
			double[] output = new double[dosages.length];
			for (int i = 0; i < output.length; i++) {
				output[i] = dosages[i][0];
			}
			return output;
		}

		private double[] convertGenotypesToDouble(VCFVariant vcfVariant) {
			DoubleMatrix2D alleles = vcfVariant.getGenotypeAllelesAsMatrix2D();
			double[] output = new double[alleles.rows()];
			for (int i = 0; i < alleles.rows(); i++) {
				if (alleles.getQuick(i, 0) == -1) {
					output[i] = -1;
				} else {
					output[i] = (alleles.getQuick(i, 0) + alleles.getQuick(i, 1));
				}
			}
			return output;
		}
	}


}
