/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package nl.harmjanwestra.utilities.features;

import nl.harmjanwestra.utilities.enums.Chromosome;
import nl.harmjanwestra.utilities.enums.Strand;

import java.util.ArrayList;

/**
 * @author Harm-Jan
 */
public class Exon extends Feature {

	private ArrayList<Transcript> transcripts;
	private final Gene gene;
	private TYPE type;

	enum TYPE {
		STARTCODON,
		STOPCODON,
		CDS,
		EXON,
		OTHER
	}

	public Exon(String name, Chromosome chr, Strand strand, Gene gene, int start, int stop) {
		this.name = name;

		if (this.name != null) {
			String lc = this.name.toLowerCase();
			if (lc.equals("start_codon")) {
				this.type = TYPE.STARTCODON;
			} else if (lc.equals("stop_codon")) {
				this.type = TYPE.STOPCODON;
			} else if (lc.equals("cds")) {
				this.type = TYPE.CDS;
			} else if (lc.equals("exon")) {
				this.type = TYPE.EXON;
			} else {
				this.type = TYPE.OTHER;
			}
		}

		this.chromosome = chr;
		this.strand = strand;

		this.gene = gene;
		this.start = start;
		this.stop = stop;

	}

	public ArrayList<Transcript> getTranscripts() {
		return transcripts;
	}

	public Gene getGene() {
		return gene;
	}

	public void addTranscript(Transcript t) {
		if (this.transcripts == null) {
			this.transcripts = new ArrayList<Transcript>();
		}
		this.transcripts.add(t);
	}

	public TYPE getType() {
		return type;
	}

	@Override
	public String toString() {
		return "Exon{" + "chromosome=" + chromosome + ", name=" + name + ", strand=" + strand + ", gene=" + gene.getName() + ", start=" + start + ", stop=" + stop + '}';
	}

}
