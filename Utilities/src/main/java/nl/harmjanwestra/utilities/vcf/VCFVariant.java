package nl.harmjanwestra.utilities.vcf;

import nl.harmjanwestra.utilities.features.Chromosome;
import nl.harmjanwestra.utilities.features.Feature;
import umcg.genetica.io.trityper.util.BaseAnnot;
import umcg.genetica.text.Strings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Created by hwestra on 4/21/15.
 */
public class VCFVariant {

	private static final int nrHeaderElems = 9;
	private static final Pattern slash = Pattern.compile("/");
	private static final Pattern pipe = Pattern.compile("\\|");
	private static final Pattern nullGenotype = Pattern.compile("\\./\\.");
	private byte[][] genotypeAllelesNew;
	private final HashMap<String, Double> info = new HashMap<String, Double>();
	HashSet<String> notSplittableElems = new HashSet<String>();
	private int[] nrAllelesObserved;
	private double[] genotypeDosages;
	private double[][] genotypeProbsNew;
	private short[] genotypeQuals;
	private short[] allelicDepths;
	private boolean monomorphic;
	private double callrate;
	private boolean multiallelic;
	private double hwep;
	private boolean biallelic = false;
	private double[] allelefrequencies;
	private String minorAllele;
	private String[] alleles = null;
	private String chr = null;
	private int pos = -1;
	private String id = null;
	private int qual = -1;
	private String filter = null;
	private double MAF;
	private String separator = "/";

	private int gtCol = -1; // genotype
	private int adCol = -1; // Allelic depths for the ref and alt alleles in the order listed
	private int dpCol = -1; // Approximate readAsTrack depth (reads with MQ=255 or with bad mates are filtered)
	private int gqCol = -1; // Genotype Quality
	private int plCol = -1; // Normalized, Phred-scaled likelihoods for genotypes as defined in the VCF specification
	private int pidCol = -1; // ?
	private int pgtCol = -1; // ?
	private int dsCol = -1;
	private int gpCol = -1;

	public enum PARSE {
		HEADER,
		GENOTYPES,
		ALL
	}

	public VCFVariant(String ln) {
		this(ln, PARSE.ALL);
	}

	private String[] tokens;

	public VCFVariant(String ln, PARSE p) {
		String[] tokenArr = Strings.tab.split(ln);//tokens.toArray(new String[0]);
		// count number of alleles with certain readAsTrack depth
		parseHeader(tokenArr);
		if (p.equals(PARSE.ALL) || p.equals(PARSE.GENOTYPES)) {
			parseGenotypes(tokenArr, p);
			recalculateMAFAndCallRate();
		} else {
			// save for future use...
			tokens = tokenArr;
		}
	}

	public void cleartokens() {
		tokens = null;
	}

	public String[] getTokens() {
		return tokens;
	}

	public void parseGenotypes(String[] tokenArr, PARSE p) {

		// parse actual genotypes.
		int nrTokens = tokenArr.length;
		byte[] alleles1 = new byte[nrTokens - nrHeaderElems];
		byte[] alleles2 = new byte[nrTokens - nrHeaderElems];
		allelicDepths = new short[nrTokens - nrHeaderElems];
		genotypeQuals = new short[nrTokens - nrHeaderElems];

		for (int t = 9; t < nrTokens; t++) {
			String token = tokenArr[t];
			int indPos = t - nrHeaderElems;
			String sampleColumn = token;
			if (nullGenotype.equals(sampleColumn)) {
				// not called
				alleles1[indPos] = -1;
				alleles2[indPos] = -1;
			} else {
				//String[] sampleElems = Strings.colon.split(sampleColumn);

				String[] sampleTokens = Strings.colon.split(sampleColumn);


				int sampleTokenCtr = 0;
				for (int s = 0; s < sampleTokens.length; s++) {
					String sampleToken = sampleTokens[s];

					if (s == gtCol) {
						String gt = sampleToken;
						if (nullGenotype.equals(gt)) {
							alleles1[indPos] = -1;
							alleles2[indPos] = -1;
						} else {
							String[] gtElems = slash.split(gt);
							separator = "/";
							if (gtElems.length == 1) { // phased genotypes
								gtElems = pipe.split(gt);
								separator = "|";
							}

							byte gt1 = 0;
							byte gt2 = 0;

							if (gtElems[0].equals(".")) {
								alleles1[indPos] = -1;
								alleles2[indPos] = -1;
							} else {
								try {
									gt1 = Byte.parseByte(gtElems[0]);
									gt2 = Byte.parseByte(gtElems[1]);

									alleles1[indPos] = gt1;
									alleles2[indPos] = gt2;

								} catch (NumberFormatException e) {
									System.out.println("Cannot parse genotype string: " + token + " nr elems: " + gtElems.length);
									alleles1[indPos] = -1;
									alleles2[indPos] = -1;
								}
							}
						}
					} else if (s == dsCol) {
						if (p.equals(PARSE.ALL)) {
							try {
								genotypeDosages[indPos] = Double.parseDouble(sampleToken);
							} catch (NumberFormatException e) {

							}
						}
					} else if (s == gpCol) {
						if (p.equals(PARSE.ALL)) {
							String[] gpElems = Strings.comma.split(sampleToken);

							try {

								if (genotypeProbsNew == null) {
									genotypeProbsNew = new double[gpElems.length][nrTokens - nrHeaderElems];
								}

								for (int g = 0; g < gpElems.length; g++) {
									genotypeProbsNew[g][indPos] = Double.parseDouble(gpElems[g]);
								}

							} catch (NumberFormatException e) {

							}
						}
					} else if (s == gqCol) {
						short gq = 0;

						try {
							if (gqCol != -1) {
								gq = Short.parseShort(sampleToken);
							}
						} catch (NumberFormatException e) {
						}
						genotypeQuals[indPos] = gq;

					} else if (s == dpCol) {
						short depth = 0;

						try {
							if (dpCol != -1) {
								depth = Short.parseShort(sampleToken);
							}
						} catch (NumberFormatException e) {
						}
						allelicDepths[indPos] = depth;
					}

					sampleTokenCtr++;
				}
			}
		}

		genotypeAllelesNew = new byte[2][];
		genotypeAllelesNew[0] = alleles1;
		genotypeAllelesNew[1] = alleles2;
	}

	private void parseHeader(String[] tokenArr) {
		// parse line header
		String ref = "";
		int nrTokens = tokenArr.length;
		for (int t = 0; t < 9; t++) {
			String token = tokenArr[t];

			switch (t) {
				case 0:
					this.chr = new String(token).intern();
					break;
				case 1:
					pos = Integer.parseInt(token);
					break;
				case 2:
					id = new String(token);
					break;
				case 3:
					ref = token;
					break;
				case 4:
					String alt = token;
					String[] alternateAlleles = alt.split(",");
					alleles = new String[1 + alternateAlleles.length];
					alleles[0] = new String(ref).intern();
					for (int i = 0; i < alternateAlleles.length; i++) {
						alleles[1 + i] = new String(alternateAlleles[i]).intern();
					}
					nrAllelesObserved = new int[alternateAlleles.length + 1];
					break;
				case 5:
					String qualStr = token;
					try {
						qual = Integer.parseInt(qualStr);
					} catch (NumberFormatException e) {

					}
					break;
				case 6:
					filter = new String(token).intern();
					break;
				case 7:
					String infoStr = token;
					String[] infoElems = Strings.semicolon.split(infoStr);

					if (!infoStr.equals(".")) {
						for (int e = 0; e < infoElems.length; e++) {
							String[] infoElemElems = Strings.equalssign.split(infoElems[e]);
							String id = new String(infoElemElems[0]).intern();
							if (infoElemElems.length > 1) {
								try {
									Double val = Double.parseDouble(infoElemElems[1]);
									info.put(id, val);
								} catch (NumberFormatException ex) {

								}
							} else {
								if (infoElems[e].equals("DB")) {
									info.put(id, 1d);
								} else if (infoElems[e].equals("PR")) {
									info.put(id, 1d);
								} else {
									if (!notSplittableElems.contains(infoElems[e])) {
										System.out.println("info: " + infoElems[e] + " not splitable");
										notSplittableElems.add(new String(infoElems[e]));
									}
								}

							}
						}
					}
					break;
				case 8:
					String[] format = Strings.colon.split(token);

					for (int c = 0; c < format.length; c++) {
						if (format[c].equals("GT")) {
							gtCol = c;
						} else if (format[c].equals("AD")) {
							adCol = c;
						} else if (format[c].equals("DP")) {
							dpCol = c;
						} else if (format[c].equals("GQ")) {
							gqCol = c;
						} else if (format[c].equals("PL")) {
							plCol = c;
						} else if (format[c].equals("PGT")) {
							pgtCol = c;
						} else if (format[c].equals("PID")) {
							pidCol = c;
						} else if (format[c].equals("DS")) {
							dsCol = c;
							genotypeDosages = new double[nrTokens - nrHeaderElems];
						} else if (format[c].equals("GP")) {
							gpCol = c;
						}
						// GT:AD:DP:GQ:PGT:PID:PL
					}

					if (gtCol == -1) {
						System.out.println("No GT COL: " + token);
						System.exit(-1);
					}
					break;
			}
		}
	}

	public boolean isMonomorphic() {
		return monomorphic;
	}

	public double getCallrate() {
		return callrate;
	}

	public boolean isMultiallelic() {
		return multiallelic;
	}

	public double getHwep() {
		return hwep;
	}

	public boolean isBiallelic() {
		return biallelic;
	}

	public double[] getAllelefrequencies() {
		return allelefrequencies;
	}

	public String[] getAlleles() {
		return alleles;
	}

	public String getMinorAllele() {
		return minorAllele;
	}

	public String getChr() {
		return chr;
	}

	public int getPos() {
		return pos;
	}

	public String getId() {
		return id;
	}

	public int getQual() {
		return qual;
	}

	public String getFilter() {
		return filter;
	}

	public short[] getGenotypeQuals() {
		return genotypeQuals;
	}

	public short[] getAllelicDepths() {
		return allelicDepths;
	}

	public double getMAF() {
		return MAF;
	}

	public int[] getNrAllelesObserved() {
		return nrAllelesObserved;
	}

	public byte[][] getGenotypeAllelesNew() {
		return genotypeAllelesNew;
	}


	public void flipReferenceAlelele() {

		String allele0 = alleles[0];
		String allele1 = alleles[1];
		alleles[0] = allele1;
		alleles[1] = allele0;

		if (genotypeAllelesNew != null) {

			// only works for biallelic variants!
			for (int i = 0; i < genotypeAllelesNew[0].length; i++) {
				for (int j = 0; j < genotypeAllelesNew.length; j++) {
					if (genotypeAllelesNew[j][i] == -1) {
						genotypeAllelesNew[j][i] = -1;
					} else {
						genotypeAllelesNew[j][i] = (byte) Math.abs(genotypeAllelesNew[j][i] - 1);
					}
				}
			}


		}
	}

	public double[][] getImputedDosages() {

		double[][] probs = getGenotypeProbsNew();
		int nrAlleles = getAlleles().length;
		double[][] dosages = new double[probs[0].length][nrAlleles - 1];
		for (int i = 0; i < probs[0].length; i++) {
			int alctr = 0;
			for (int a1 = 0; a1 < nrAlleles; a1++) {
				for (int a2 = a1; a2 < nrAlleles; a2++) {
					double dosageval = probs[alctr][i];
					if (a1 > 0) {
						dosages[i][a1 - 1] += dosageval;
					}
					if (a2 > 0) {
						dosages[i][a2 - 1] += dosageval;
					}
					alctr++;
				}
			}
		}

		return dosages;
	}

	public Boolean alleleFlip(VCFVariant var2) {

		if (isBiallelic() && var2.isBiallelic()) {
			return BaseAnnot.flipalleles(allelesAsString(), minorAllele, var2.allelesAsString(), var2.getMinorAllele());
		} else {
			System.out.println("WARNING: multi allelic allele flip not implemented at this point!");
			return false;
		}


	}

	public double[][] getDosages() {
		int nrAlleles = getAlleles().length;
		double[][] output = new double[genotypeAllelesNew[0].length][nrAlleles - 1]; // allow for multiple alleles
		for (int i = 0; i < output.length; i++) {
			for (int j = 0; j < genotypeAllelesNew.length; j++) {
				int a = (int) genotypeAllelesNew[j][i];


				if (a == -1) {
					// missing genotype
					output[i][0] = Double.NaN;
				} else if (a > 0) {
					if (a > 0) {
						a -= 1;
					}
					output[i][a]++;
				}

			}
		}

		return output;
	}

	public String allelesAsString() {
		return Strings.concat(alleles, Pattern.compile("/"));
	}

	public void recodeAlleles(HashMap<String, Integer> alleleMap, String[] newAlleles) {


		int[] alleleRecode = new int[alleles.length];
		boolean allelesremoved = false;
		int allelesremain = 0;
		ArrayList<String> remainingalleles = new ArrayList<String>(3);
		for (int i = 0; i < alleles.length; i++) {
			Integer alleleCd = alleleMap.get(alleles[i]);
			;
			if (alleleCd != null) {
				alleleRecode[i] = alleleCd;
				allelesremain++;
				remainingalleles.add(alleles[i]);
			} else {
				System.out.println("Removing allele: " + alleles[i] + " from variant: " + chr + ":" + pos);
				alleleRecode[i] = -1;
				allelesremoved = true;
			}
		}
		if (allelesremoved) {
			System.out.println(allelesremain + " alleles remain for variant " + chr + ":" + pos + " prev: " + Strings.concat(alleles, Strings.comma) + "\tnew: " + Strings.concat(remainingalleles, Strings.comma));
		}


		if (genotypeAllelesNew != null) {
			for (int i = 0; i < genotypeAllelesNew[0].length; i++) {
				for (int j = 0; j < genotypeAllelesNew.length; j++) {
					if (genotypeAllelesNew[j][i] == -1) {
						genotypeAllelesNew[j][i] = -1;
					} else {
						if (alleleRecode[genotypeAllelesNew[j][i]] == -1) {
							System.err.println("Allele " + alleleRecode[genotypeAllelesNew[j][i]] + " removed!");
							System.exit(-1);
						}
						genotypeAllelesNew[j][i] = (byte) alleleRecode[genotypeAllelesNew[j][i]];
					}
				}
			}
		}

		alleles = newAlleles;
	}

	public void convertAllelesToComplement() {
		alleles = convertToComplement(alleles);
	}

	private String[] convertToComplement(String[] alleles2) {
		String[] complement = new String[alleles2.length];
		for (int i = 0; i < complement.length; i++) {
			String allele = alleles2[i];
			complement[i] = getComplement(allele);

		}
		return complement;
	}

	private String getComplement(String allele) {
		String out = "";
		for (int j = 0; j < allele.length(); j++) {
			char c = allele.charAt(j);
			if (c == 'A') {
				out += "T";
			} else if (c == 'T') {
				out += "A";
			} else if (c == 'G') {
				out += "C";
			} else if (c == 'C') {
				out += "G";
			} else {
				out += "N";
			}
		}
		return out;
	}

	public String toVCFString() {
		StringBuilder builder = new StringBuilder(100000);
		builder.append(chr);
		builder.append("\t");
		builder.append(pos);
		builder.append("\t");
		builder.append(id);
		builder.append("\t");
		builder.append(alleles[0]);
		builder.append("\t");
		builder.append(Strings.concat(alleles, Strings.comma, 1, alleles.length));
		builder.append("\t.\t.\t.\tGT");


		for (int i = 0; i < genotypeAllelesNew[0].length; i++) {
			String al1 = "" + genotypeAllelesNew[0][i];
			String al2 = "" + genotypeAllelesNew[1][i];
			if (genotypeAllelesNew[0][i] == -1) {
				al1 = ".";
			}
			if (genotypeAllelesNew[1][i] == -1) {
				al2 = ".";
			}
			builder.append("\t");
			builder.append(al1).append(separator).append(al2);

		}
		return builder.toString();
	}

	@Override
	public String toString() {
//		return this.chr + "-" + this.pos + ";" + this.id;
		return this.chr + "-" + this.pos + "-" + this.id;
	}

	public double[] getGenotypeDosages() {
		return genotypeDosages;
	}

	public double[][] getGenotypeProbsNew() {
		return genotypeProbsNew;
	}

	public HashMap<String, Double> getInfo() {
		return info;
	}

	public boolean hasImputationProbs() {
		return genotypeProbsNew != null;
	}

	public void recalculateMAFAndCallRate() {
		recalculateMAFAndCallRate(null);
	}

	public void recalculateMAFAndCallRate(Boolean[] individualIsFemale) {

		int nrCalled = 0;
		nrAllelesObserved = new int[nrAllelesObserved.length];
		int[] nrAllelesObservedLocal = new int[nrAllelesObserved.length];
		int nrIndividuals = genotypeAllelesNew[0].length;
		Chromosome chromosome = Chromosome.parseChr(chr);
		if (individualIsFemale != null) {
			int nrFemales = 0;
			int nrMales = 0;
			for (int i = 0; i < genotypeAllelesNew[0].length; i++) {
				if (individualIsFemale[i] != null) {
					if (individualIsFemale[i]) {
						nrFemales++;
					} else {
						nrMales++;
					}
				}
			}
			if (chromosome.equals(Chromosome.X)) {
				nrIndividuals = nrFemales;
			} else if (chromosome.equals(Chromosome.Y)) {
				nrIndividuals = nrMales;
			} else {
				nrIndividuals = genotypeAllelesNew[0].length;
			}
		}


		for (int i = 0; i < genotypeAllelesNew[0].length; i++) {
			if (chromosome.equals(Chromosome.X)) {
				if ((individualIsFemale != null && individualIsFemale[i] != null && individualIsFemale[i])) {
					if (genotypeAllelesNew[0][i] != -1) {
						nrCalled++;
						nrAllelesObservedLocal[genotypeAllelesNew[0][i]]++;
						nrAllelesObservedLocal[genotypeAllelesNew[1][i]]++;
					}
				} else if (individualIsFemale == null) {
					System.err.println("ERROR: cannot calculate chr X MAF if gender information unavailable.");
					throw new IllegalArgumentException("blaaat");
				}
			} else if (chromosome.equals(Chromosome.Y)) {
				if ((individualIsFemale != null && individualIsFemale[i] != null && !individualIsFemale[i])) {
					if (genotypeAllelesNew[0][i] != -1) {
						nrCalled++;
						nrAllelesObservedLocal[genotypeAllelesNew[0][i]]++;
						nrAllelesObservedLocal[genotypeAllelesNew[1][i]]++;
					}
				} else if (individualIsFemale == null) {
					System.err.println("ERROR: cannot calculate chr Y MAF if gender information unavailable.");
				}
			} else {
				if (genotypeAllelesNew[0][i] != -1) {
					nrCalled++;
					nrAllelesObservedLocal[genotypeAllelesNew[0][i]]++;
					nrAllelesObservedLocal[genotypeAllelesNew[1][i]]++;
				}
			}

			if (genotypeAllelesNew[0][i] != -1) {
				nrAllelesObserved[genotypeAllelesNew[0][i]]++;
				nrAllelesObserved[genotypeAllelesNew[1][i]]++;
			}

		}

		callrate = (double) nrCalled / nrIndividuals;


		int totalAllelesObs = nrCalled * 2;

		int nrAllelesThatHaveAlleleFrequency = 0;
		double minAlleleFreq = 2;
		allelefrequencies = new double[nrAllelesObserved.length];
		minorAllele = null;

		for (int i = 0; i < nrAllelesObserved.length; i++) {
			double alleleFreq = (double) nrAllelesObservedLocal[i] / totalAllelesObs;
			allelefrequencies[i] = alleleFreq;

			if (nrAllelesObservedLocal[i] > 0) {
				nrAllelesThatHaveAlleleFrequency++;
				if (alleleFreq < minAlleleFreq) {
					if (i == 0) {
						minorAllele = alleles[0];
					} else {
						minorAllele = alleles[i];
					}
					minAlleleFreq = alleleFreq;
				}
			}
		}

		MAF = minAlleleFreq;
		if (MAF == 1) { // flip alleles if monomorphic
			MAF = 0;
			if (minorAllele.equals(alleles[0])) {
				minorAllele = alleles[1];
			} else {
				minorAllele = alleles[0];
			}
		}

		if (nrAllelesThatHaveAlleleFrequency == 2) {
			biallelic = true;

			// TODO: calculate HWE P
			hwep = 0;
		} else if (nrAllelesThatHaveAlleleFrequency > 2) {
			multiallelic = true;
		} else {
			monomorphic = true;
		}
	}


	public String getInfoString() {

		if (info == null || info.isEmpty()) {
			return ".";
		} else {
			Set<String> keys = info.keySet();
			String[] infoStr = new String[keys.size()];
			int keyctr = 0;
			for (String key : keys) {
				Object infoObj = info.get(key);
				infoStr[keyctr] = infoObj.toString();
				keyctr++;
			}
			String output = Strings.concat(infoStr, Strings.semicolon);
			return output;
		}
	}

	public Feature asFeature() {
		Feature output = new Feature(Chromosome.parseChr(chr), pos, pos);
		output.setName(id);
		return output;

	}
}
