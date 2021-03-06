package nl.harmjanwestra.utilities.features;

import nl.harmjanwestra.utilities.enums.Chromosome;

/**
 * Created by hwestra on 11/11/15.
 */
public class SNPFeature extends Feature {
	double p;

	private double imputationQualScore;
	private String[] alleles;
	private String minorAllele;
	private double maf;
	private double hwep;
	private double cr;
	private double[] AFCases;
	private double[] AFControls;
	private double missingnessP;
	private double crCases;
	private double crControls;
	
	
	public double getMaf() {
		return maf;
	}

	public void setMaf(double maf) {
		this.maf = maf;
	}

	public double getHwep() {
		return hwep;
	}

	public void setHwep(double hwep) {
		this.hwep = hwep;
	}

	public double getCr() {
		return cr;
	}

	public void setCr(double cr) {
		this.cr = cr;
	}

	public SNPFeature() {

	}

	public SNPFeature(SNPFeature f2) {
		super(f2);
		this.p = f2.getP();
	}

	public SNPFeature(Chromosome chr, int start, int end) {
		super(chr, start, end);
	}

	public double getImputationQualityScore() {
		return imputationQualScore;
	}

	public void setImputationQualityScore(double imputationQualScore) {
		this.imputationQualScore = imputationQualScore;
	}

	public String[] getAlleles() {
		return alleles;
	}

	public void setAlleles(String[] alleles) {
		this.alleles = alleles;
	}

	public String getMinorAllele() {
		return minorAllele;
	}

	public void setMinorAllele(String minorAllele) {
		this.minorAllele = minorAllele;
	}

	public double getP() {
		return p;
	}

	public void setP(double p) {
		this.p = p;
	}

	@Override
	public String toString() {
		return getChromosome().getName() + "_" + getStart() + "_" + name;
	}

	public void setAFCases(double AFCases) {
		this.AFCases = new double[]{
				AFCases
		};
	}

	public void setAFCases(double[] AFCases) {
		this.AFCases = AFCases;
	}

	public void setAFControls(double AFControls) {
		this.AFControls = new double[]{AFControls};
	}

	public void setAFControls(double[] AFControls) {
		this.AFControls = AFControls;
	}

	public double getAFCases() {
		if (AFCases == null) {
			return 0;
		} else {
			return AFCases[0];
		}

	}

	public double getAFControls() {
		if (AFControls == null) {
			return 0;
		} else {
			return AFControls[0];
		}

	}


	public static SNPFeature parseSNPFeature(String str) {

		String[] elems = str.split("_");
		if (elems.length == 3) {
			Chromosome chr = Chromosome.parseChr(elems[0]);

			Integer s1 = Integer.parseInt(elems[1]);
			String name = elems[2];

			SNPFeature out = new SNPFeature(chr, s1, s1);
			out.setName(name);

			return out;
		} else {
			return null;
		}
	}

	public double[] getAFCasesArray() {
		return AFCases;
	}

	public double[] getAFControlsArray() {
		return AFControls;
	}

	public boolean isIndel() {
		for (String a : alleles) {
			if (a.length() > 1) {
				return true;
			}
		}
		return false;
	}

	public void setMissingnessP(double missingnessP) {
		this.missingnessP = missingnessP;
	}

	public double getMissingnessP() {
		return missingnessP;
	}
	
	public void setCrCases(double crCases) {
		this.crCases = crCases;
	}
	
	public void setCrControls(double crControls) {
		this.crControls = crControls;
	}
	
	public double getCrCases() {
		return crCases;
	}
	
	public double getCrControls() {
		return crControls;
	}
}
