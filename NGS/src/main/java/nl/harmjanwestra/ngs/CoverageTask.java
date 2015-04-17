package nl.harmjanwestra.ngs;

import htsjdk.samtools.*;
import htsjdk.samtools.filter.AggregateFilter;
import htsjdk.samtools.filter.SamRecordFilter;
import nl.harmjanwestra.utilities.bamfile.BamFileReader;
import nl.harmjanwestra.utilities.features.Chromosome;
import nl.harmjanwestra.utilities.features.Feature;
import nl.harmjanwestra.utilities.features.FeatureComparator;
import org.broadinstitute.gatk.engine.filters.*;
import org.broadinstitute.gatk.tools.walkers.haplotypecaller.HCMappingQualityFilter;
import umcg.genetica.io.Gpio;
import umcg.genetica.io.text.TextFile;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Created by hwestra on 3/30/15.
 */
public class CoverageTask implements Callable<Boolean> {


	private final String bamfile;
	private final String outdir;
	private final String regionfile;
	private final boolean outputcoverageperregion;

	private ArrayList<Feature> loadRegions(String regionfile) throws IOException {
		ArrayList<Feature> regions = new ArrayList<Feature>();

		TextFile featurefile = new TextFile(regionfile, TextFile.R);
		String[] felems = featurefile.readLineElems(TextFile.tab);
		while (felems != null) {

			if (felems.length >= 3) {
				Feature f = new Feature();
				Chromosome c = Chromosome.parseChr(felems[0]);
				f.setChromosome(c);
				f.setStart(Integer.parseInt(felems[1]));
				f.setStop(Integer.parseInt(felems[2]));
				regions.add(f);
			}

			felems = featurefile.readLineElems(TextFile.tab);
		}
		featurefile.close();


		// now sort them
		Collections.sort(regions, new FeatureComparator(false));
		return regions;
	}

	public CoverageTask(String bamfile, String outdir, String regionfile, boolean outputcoverageperregion) {
		this.bamfile = bamfile;
		this.outdir = outdir;
		this.regionfile = regionfile;
		this.outputcoverageperregion = outputcoverageperregion;
	}

	public void bamToBedWithinRegions(String bamfile, String outdir, String regionFile, boolean outputcoverageperregion) throws IOException {


		ArrayList<Feature> regions = loadRegions(regionFile);

		BamFileReader reader = new BamFileReader(new File(bamfile));
		List<SAMReadGroupRecord> readGroups = reader.getReadGroups();
		HashMap<SAMReadGroupRecord, Integer> readgroupMap = new HashMap<SAMReadGroupRecord, Integer>();
		String[] samples = null;

		Gpio.createDir(outdir);
		String regionOutdir = outdir + "regions/";

		if (readGroups.size() > 0) {
			samples = new String[readGroups.size()];
			int rgctr = 0;
			for (SAMReadGroupRecord r : readGroups) {
				readgroupMap.put(r, rgctr);
				String sample = r.getSample();
				sample = sample.replaceAll("/", "-");
				samples[rgctr] = sample;
				rgctr++;
			}
		} else {
			System.err.println("WARNING: no readgroups found. Assuming single sample.");
			samples = new String[1];

			samples[0] = bamfile;


		}

		ArrayList<SamRecordFilter> filters = new ArrayList<SamRecordFilter>();
		AggregateFilter filter = new AggregateFilter(filters);

		filters.add(new NotPrimaryAlignmentFilter());
		filters.add(new FailsVendorQualityCheckFilter());
		filters.add(new DuplicateReadFilter());
		filters.add(new UnmappedReadFilter());
		filters.add(new MappingQualityUnavailableFilter());
		filters.add(new HCMappingQualityFilter());
		// ilters.add(new MalformedReadFilter());

		int[][] data = new int[samples.length][15];
		int[][] mapqDist = new int[samples.length][200];

		double[][] avgcoverage = new double[samples.length][regions.size()];
		double[][] avgmapq = new double[samples.length][regions.size()];
		double[][] readsperregion = new double[samples.length][regions.size()];
		double[][] nrBasesPerRegionAboveThreshold = new double[samples.length][regions.size()];

		long[][] basesWCertainCoverage = new long[samples.length][500];

		int ln = 0;
		int ln2 = 0;

		int fct = 0;


		HashMap<String, String> chromosomeToSequence = new HashMap<String, String>();

		// hash the chromosome names (because of stupid non-conventional chromosome names)
		for (Feature f : regions) {
			String name = f.getChromosome().getName();
			if (!chromosomeToSequence.containsKey(name)) {
				boolean match = false;
				int format = 0;
				while (!match) {
					SAMSequenceRecord record = null;
					String newname = null;
					switch (format) {
						case 0:

							record = reader.getHeader().getSequence(name);
							if (record != null) {
								match = true;
								chromosomeToSequence.put(name, name);
							}
							break;
						case 1:
							newname = name.toLowerCase();
							record = reader.getHeader().getSequence(newname);
							if (record != null) {
								match = true;
								chromosomeToSequence.put(name, newname);
							}
							break;
						case 2:
							newname = name.replaceAll("chr", "");
							newname = newname.replaceAll("Chr", "");
							record = reader.getHeader().getSequence(newname);
							if (record != null) {
								match = true;
								chromosomeToSequence.put(name, newname);
							}
							break;
						case 3:
							chromosomeToSequence.put(name, null);
							System.out.println("Could not find chr in bamfile: " + name);
							match = true;
							break;
					}


					format++;
				}
			}
		}


		TextFile[] bedout = new TextFile[samples.length];
		for (int i = 0; i < samples.length; i++) {
			String[] samplenameelems = samples[i].split("/");
			String sampleName = samplenameelems[samplenameelems.length - 1];
			bedout[i] = new TextFile(outdir + sampleName + "-regions.bedGraph", TextFile.W);
		}


		int fctr = 0;
		for (Feature f : regions) {
			Chromosome c = f.getChromosome();
			int start = f.getStart();
			int stop = f.getStop();


			String chrName = chromosomeToSequence.get(c.getName());
			if (chrName != null) {
				SAMRecordIterator it = reader.query(chrName, start - 1000, stop + 1000, true);

				int windowSize = stop - start;
				int[][] tmpCoverage = new int[samples.length][stop - start];

				if (it.hasNext()) {
					SAMRecord record = it.next();


					while (it.hasNext()) {

						if (!filter.filterOut(record)) {
							SAMReadGroupRecord rg = record.getReadGroup();
							Integer sampleId = readgroupMap.get(rg);
							if (samples.length == 1) {
								sampleId = 0;
							}

							if ((record.getAlignmentStart() >= start && record.getAlignmentEnd() <= stop) ||
									(record.getAlignmentStart() <= start && record.getAlignmentEnd() >= start) ||
									(record.getAlignmentStart() >= start && record.getAlignmentStart() <= stop)) {
								readsperregion[sampleId][fct]++;
								avgmapq[sampleId][fct] += record.getMappingQuality();
								if (record.getDuplicateReadFlag()) {
// 1
									data[sampleId][0]++;
								}
								if (record.getReadPairedFlag() && record.getFirstOfPairFlag()) {
// 2
									data[sampleId][1]++;
								}

								if (record.getReadPairedFlag() && record.getMateNegativeStrandFlag()) {
// 3
									data[sampleId][2]++;
								}

								if (record.getReadPairedFlag() && record.getMateUnmappedFlag()) {
// 4
									data[sampleId][3]++;
								}

								if (record.getNotPrimaryAlignmentFlag()) {
// 5
									data[sampleId][4]++;
								}

								if (record.getReadPairedFlag() && record.getProperPairFlag()) {
// 6
									data[sampleId][5]++;
								}

								if (record.getReadFailsVendorQualityCheckFlag()) {
// 7
									data[sampleId][6]++;
								}

								if (record.getReadNegativeStrandFlag()) {
// 8
									data[sampleId][7]++;
								}

								if (record.getReadPairedFlag()) {
// 9
									data[sampleId][8]++;
								}

								if (record.getReadUnmappedFlag()) {
// 10
									data[sampleId][9]++;
								}

								if (record.getReadPairedFlag() && record.getSecondOfPairFlag()) {
// 11
									data[sampleId][11]++;
								}

								if (filter.filterOut(record)) {
// 12
									data[sampleId][12]++; // nr of reads filtered out by HC filter
								}

								data[sampleId][13]++; // total nr of reads
								int mapq = record.getMappingQuality();


								if (mapq > mapqDist[0].length) {
									mapq = mapqDist[0].length - 1;
								}
								mapqDist[sampleId][mapq]++;

								String refname = record.getReferenceName();
								int len = record.getReadLength();
								int sta = record.getAlignmentStart();
								int sto = record.getAlignmentEnd();
								int insert = record.getInferredInsertSize();


								int mapPos = record.getAlignmentStart();
								int windowRelativePosition = mapPos - start;


								int readPosition = 0;

								byte[] baseQual = record.getBaseQualities();
								byte[] bases = record.getReadBases();

								Cigar cigar = record.getCigar();
								List<CigarElement> cigarElements = cigar.getCigarElements();

								if (bases == null || bases.length == 0 || baseQual == null || baseQual.length == 0) {
									System.err.println("No bases for read: " + record.getReadUnmappedFlag() + "\t" + record.toString() + "\t" + bases.length + "\t" + baseQual.length);
								} else {
									for (CigarElement e : cigarElements) {
										int cigarElementLength = e.getLength();

										switch (e.getOperator()) {
											case H:
												break;
											case P:
												break;
											case S: // soft clip
												readPosition += cigarElementLength;
												break;
											case N: // ref skip
												windowRelativePosition += cigarElementLength;
												break;
											case D: // deletion
												windowRelativePosition += cigarElementLength;
												break;
											case I: // insertion
												windowRelativePosition += cigarElementLength;
												break;
											case M:
											case EQ:
											case X:
												int endPosition = readPosition + cigarElementLength;
												for (int pos = readPosition; pos < endPosition; pos++) {
													boolean properbase = false;
													byte base = bases[pos];

													if (windowRelativePosition >= 0 && windowRelativePosition < windowSize) { // the read could overlap the leftmost edge of this window
														if (base == 78 || base == 110) {
															// N

														} else if (readPosition < baseQual.length && baseQual[readPosition] > 0) { //    -- for each base pos: check whether basequal > 50
															//    -- determine number of A/T/C/G/N bases
															if (base == 65 || base == 97) {

																properbase = true;
															} else if (base == 67 || base == 99) {

																properbase = true;
															} else if (base == 71 || base == 103) {

																properbase = true;
															} else if (base == 84 || base == 116) { // extend to capture U?

																properbase = true;
															}
														} else if (baseQual[readPosition] > 0) {
															System.err.println("Unknown base found! " + base);
														}
													}

													if (properbase) {
														tmpCoverage[sampleId][windowRelativePosition]++;
													}
													windowRelativePosition++;
												} // if pos < readposition
												readPosition += cigarElementLength;
												break;
											default:
												System.err.println("Unknown CIGAR operator found: " + e.getOperator().toString());
												System.err.println("In read: " + record.toString());
												break;
										} // switch operator
									} // for each cigar element
								}
							}
						}
						record = it.next();
					}
				}

				for (int i = 0; i < samples.length; i++) {
					avgmapq[i][fct] /= readsperregion[i][fct];
					double sum = 0;
//					String ctOut = samples[i];
					for (int q = 0; q < windowSize; q++) {
						sum += tmpCoverage[i][q];
//						ctOut += "\t" + tmpCoverage[i][q];
					}
					sum /= windowSize;
					avgcoverage[i][fct] = sum;
				}

				fct++;
				it.close();

				if (outputcoverageperregion) {
					for (int s = 0; s < samples.length; s++) {
						for (int i = 0; i < windowSize; i++) {
							if (tmpCoverage[s][i] > 0) {
								bedout[s].writeln(f.getChromosome().getName() + "\t" + (f.getStart() + i) + "\t" + (f.getStart() + i) + "\t" + tmpCoverage[s][i]);
							}
						}

					}


//					if (!Gpio.exists(regionOutdir)) {
//						Gpio.createDir(regionOutdir);
//					}
//
//					String regionFileName = regionOutdir + returnFileNameForRegion(f);
//					TextFile regionOutFile = new TextFile(regionFileName, TextFile.W);
//					for (int s = 0; s < samples.length; s++) {
//						String lnout = samples[s];
//						for (int i = 0; i < windowSize; i++) {
//							lnout += "\t" + tmpCoverage[s][i];
//						}
//						regionOutFile.writeln(lnout);
//					}
//					regionOutFile.close();
				}

				for (int i = 0; i < samples.length; i++) {
					for (int q = 0; q < windowSize; q++) {
						int cov = tmpCoverage[i][q];
						if (cov >= basesWCertainCoverage[i].length) {
							cov = basesWCertainCoverage[i].length - 1;
						}
						basesWCertainCoverage[i][cov]++;
					}
				}

				// determine the number of bases per region above 20x coverage
				for (int i = 0; i < samples.length; i++) {
					int nrBasesAboveThreshold = 0;
					for (int q = 0; q < windowSize; q++) {
						if (tmpCoverage[i][q] > 20) {
							nrBasesAboveThreshold++;
						}
					}

					nrBasesPerRegionAboveThreshold[i][fctr] = (double) nrBasesAboveThreshold / windowSize;

				}

			}
			fctr++;
			int perc = (int) Math.ceil(((double) fctr / regions.size()) * 100);
			//System.out.print(perc + "% done " + fctr + "\t" + regions.size() + "\r");

		}
		//System.out.println();

		for (int i = 0; i < samples.length; i++) {
			bedout[i].close();
		}

		TextFile hist = new TextFile(outdir + "histogram.txt.gz", TextFile.W);
		TextFile hist2 = new TextFile(outdir + "histogramCumulative.txt.gz", TextFile.W);
		long[] sumBasesPerSample = new long[samples.length];
		String histheader = "#";
		for (int s = 0; s < samples.length; s++) {
			histheader += "\t" + samples[s];
			for (int i = 0; i < basesWCertainCoverage[0].length; i++) {
				sumBasesPerSample[s] += basesWCertainCoverage[s][i];
			}
		}
		hist.writeln(histheader);
		hist2.writeln(histheader);

		double[][] cumulativePerSample = new double[samples.length][basesWCertainCoverage[0].length];
		for (int i = 0; i < basesWCertainCoverage[0].length; i++) {
			String outln = "" + i;
			String outln2 = "" + i;
			for (int s = 0; s < samples.length; s++) {
				double perc = (double) basesWCertainCoverage[s][i] / sumBasesPerSample[s];
				if (i == 0) {
					cumulativePerSample[s][i] = perc;
				} else {
					cumulativePerSample[s][i] = perc + cumulativePerSample[s][i - 1];
				}
				outln += "\t" + perc;
				outln2 += "\t" + cumulativePerSample[s][i];
			}
			hist.writeln(outln);
			hist2.writeln(outln2);
		}
		hist.close();
		hist2.close();

		TextFile summary = new TextFile(outdir + "summary.txt.gz", TextFile.W);
		String header = "-\tgetDuplicateReadFlag" +
				"\tgetFirstOfPairFlag" +
				"\tgetMateNegativeStrandFlag" +
				"\tgetMateUnmappedFlag" +
				"\tgetNotPrimaryAlignmentFlag" +
				"\tgetProperPairFlag" +
				"\tgetReadFailsVendorQualityCheckFlag" +
				"\tgetReadPairedFlag" +
				"\tgetReadUnmappedFlag" +
				"\tgetSecondOfPairFlag" +
				"\tHCfilterOut" +
				"\tTotalReads" +
				"\tpairsNoDupPrimaryProperPairPairedBothMapped";
		summary.writeln(header);
		for (int s = 0; s < data.length; s++) {
			String outputStr = samples[s];
			for (int d = 0; d < data[s].length; d++) {
				outputStr += "\t" + data[s][d];
			}
			summary.writeln(outputStr);
		}
		summary.close();

		TextFile summarymapq = new TextFile(outdir + "mapq.txt.gz", TextFile.W);
		header = "mapq";
		for (int s = 0; s < data.length; s++) {
			header += "\t" + samples[s];
		}
		summarymapq.writeln(header);
		for (int d = 0; d < mapqDist[0].length; d++) {
			String outln = d + "";
			for (int s = 0; s < data.length; s++) {
				outln += "\t" + mapqDist[s][d];
			}
			summarymapq.writeln(outln);
		}

		summarymapq.close();


		fct = 0;


		TextFile outf1 = new TextFile(outdir + "region-coverage.txt.gz", TextFile.W);
		TextFile outf2 = new TextFile(outdir + "region-mapq.txt.gz", TextFile.W);
		TextFile outf3 = new TextFile(outdir + "region-reads.txt.gz", TextFile.W);
		TextFile outf4 = new TextFile(outdir + "region-percreadsgt20.txt.gz", TextFile.W);

		header = "-";
		for (Feature f : regions) {
			header += "\t" + f.getChromosome().getName() + ":" + f.getStart() + "-" + f.getStop();
		}
		outf1.writeln(header);
		outf2.writeln(header);
		outf3.writeln(header);
		outf4.writeln(header);

		for (int s = 0; s < samples.length; s++) {
			String outln1 = samples[s];
			String outln2 = samples[s];
			String outln3 = samples[s];
			String outln4 = samples[s];
			for (int r = 0; r < regions.size(); r++) {
				outln1 += "\t" + avgcoverage[s][r];
				outln2 += "\t" + avgmapq[s][r];
				outln3 += "\t" + readsperregion[s][r];
				outln4 += "\t" + nrBasesPerRegionAboveThreshold[s][r];
			}

			outf1.writeln(outln1);
			outf2.writeln(outln2);
			outf3.writeln(outln3);
			outf4.writeln(outln4);

		}

		outf1.close();
		outf2.close();
		outf3.close();
		outf4.close();
		reader.close();
	}

	@Override
	public Boolean call() throws Exception {
		bamToBedWithinRegions(bamfile, outdir, regionfile, outputcoverageperregion);
		return true;
	}
}
