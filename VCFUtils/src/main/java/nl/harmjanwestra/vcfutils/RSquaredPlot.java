package nl.harmjanwestra.vcfutils;

import com.itextpdf.text.DocumentException;
import nl.harmjanwestra.utilities.bedfile.BedFileReader;
import nl.harmjanwestra.utilities.features.Chromosome;
import nl.harmjanwestra.utilities.features.Feature;
import nl.harmjanwestra.utilities.features.FeatureComparator;
import nl.harmjanwestra.utilities.graphics.Grid;
import nl.harmjanwestra.utilities.graphics.Range;
import nl.harmjanwestra.utilities.graphics.panels.ScatterplotPanel;
import nl.harmjanwestra.utilities.vcf.VCFVariant;
import umcg.genetica.containers.Triple;
import umcg.genetica.io.text.TextFile;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/**
 * Created by hwestra on 1/11/16.
 */
public class RSquaredPlot {


	public void plot(String filedef, String outfile, double threshold, double mafThreshold, String bedfile) throws IOException, DocumentException {


		System.out.println("infiles:\t" + filedef);
		System.out.println("outfile:\t" + outfile);
		System.out.println("rsqthreshold:\t" + threshold);
		System.out.println("mafthreshold:\t" + mafThreshold);
		System.out.println("bedfile:\t" + bedfile);

		// load R-squared values for each dataset
		// and count all unique variants

		TextFile tf = new TextFile(filedef, TextFile.R);
		String[] elems = tf.readLineElems(TextFile.tab);


		int submitted = 0;
		int cores = Runtime.getRuntime().availableProcessors();
		ExecutorService executor = Executors.newFixedThreadPool(cores);
		ExecutorCompletionService<Triple<String, ArrayList<Double>, ArrayList<String>>> completionService = new ExecutorCompletionService<>(executor);

		while (elems != null) {

			if (elems.length >= 2) {
				String ref = elems[0];
				String file = elems[1];

				LoadTask task = new LoadTask(file, ref, mafThreshold, bedfile);
				completionService.submit(task);

				submitted++;

			}
			elems = tf.readLineElems(TextFile.tab);
		}
		tf.close();

		System.out.println(submitted + " files submitted for loading.");


		HashMap<String, ArrayList<Double>> values = new HashMap<>();
		HashSet<String> allVariants = new HashSet<String>();

		int returned = 0;

		while (returned < submitted) {
			try {
				Triple<String, ArrayList<Double>, ArrayList<String>> result = completionService.take().get();
				String ref = result.getLeft();
				ArrayList<Double> rsquareds = result.getMiddle();
				ArrayList<String> vars = result.getRight();

				ArrayList<Double> vals = values.get(ref);
				if (vals == null) {
					values.put(ref, rsquareds);
				} else {
					vals.addAll(rsquareds);
				}
				allVariants.addAll(vars);
				returned++;
				System.out.println(returned + "/" + submitted + " files returned..");
			} catch (InterruptedException e) {
				e.printStackTrace();
			} catch (ExecutionException e) {
				e.printStackTrace();
			}
		}

		System.out.println("Done loading.");
		System.out.println(allVariants.size() + " variants in total");

		// sort each dataset by R-squared
		// plot each r-squared value (X/Y scatter)

		Grid grid = new Grid(400, 300, 1, 1, 100, 100);

		ScatterplotPanel panel = new ScatterplotPanel(1, 1);


		double[][] x = new double[values.size()][allVariants.size()];
		double[][] y = new double[values.size()][allVariants.size()];

		Range dataRange = new Range(0, 0, 1, 1);


		Set<String> keys = values.keySet();

		int ctr = 0;
		String[] refNames = new String[keys.size()];
		for (String key : keys) {
			ArrayList<Double> d = values.get(key);
			d = removeNaN(d);
			Collections.sort(d, Collections.reverseOrder());
			int nrAboveThreshold = 0;
			for (int v = 0; v < d.size(); v++) {
				if (v > 0) {
					x[ctr][v] = (double) v / allVariants.size();
					y[ctr][v] = d.get(v);
					if (d.get(v) >= threshold) {
						nrAboveThreshold++;
					}
				} else {
					x[ctr][v] = 0;
					y[ctr][v] = d.get(v);
				}
			}
			for (int v = d.size(); v < y[0].length; v++) {
				y[ctr][v] = Double.NaN;
				x[ctr][v] = Double.NaN;
			}
			refNames[ctr] = key + " (" + nrAboveThreshold + " / " + d.size() + ")";
			ctr++;
		}


		panel.setData(x, y);
		panel.setDataRange(dataRange);
		panel.setTitle("Imputation quality per reference panel (" + allVariants.size() + " total variants)");
		panel.setLabels("Variants", "Imputation R-squared");

		panel.setDatasetLabels(refNames);

		grid.addPanel(panel);


		grid.draw(outfile);
		System.out.println("Done. Plot is here: " + outfile);

		executor.shutdown();

	}

	private ArrayList<Double> removeNaN(ArrayList<Double> d) {
		ArrayList<Double> output = new ArrayList<>();
		for (double d2 : d) {
			if (!Double.isNaN(d2) && !Double.isInfinite(d2)) {
				output.add(d2);
			}
		}
		return output;
	}

	class LoadTask implements Callable<Triple<String, ArrayList<Double>, ArrayList<String>>> {

		String file;
		String ref;
		double threshold;
		String bedfile;

		public LoadTask(String file, String ref, double threshold, String bedfile) {
			this.file = file;
			this.ref = ref;
			this.threshold = threshold;
			this.bedfile = bedfile;
		}

		@Override
		public Triple<String, ArrayList<Double>, ArrayList<String>> call() throws Exception {

			BedFileReader reader = new BedFileReader();
			ArrayList<Feature> bedregions = null;
			if (bedfile != null) {
				bedregions = reader.readAsList(bedfile);
				Collections.sort(bedregions, new FeatureComparator(false));
				System.out.println(bedregions.size() + " bed regions read from: " + bedfile);
			}

			TextFile tf2 = new TextFile(file, TextFile.R);
			ArrayList<Double> refVals = new ArrayList<Double>();
			ArrayList<String> allVariants = new ArrayList<String>();
			System.out.println("Ref: " + ref + " file: " + file);
			String line = tf2.readLine();
			int lnctr = 0;
			while (line != null) {

				if (line.startsWith("#")) {

				} else {

					StringTokenizer tokenizer = new StringTokenizer(line);
					int ctr = 0;
					String[] tokens = new String[3];
					while (tokenizer.hasMoreTokens() && ctr < 3) {
						tokens[ctr] = tokenizer.nextToken();
						ctr++;
					}

					String chr = tokens[0];
					int snppos = Integer.parseInt(tokens[1]);

					Feature f = new Feature(Chromosome.parseChr(chr), snppos, snppos);
					boolean overlaps = false;

					if (bedregions != null) {
						for (Feature r : bedregions) {
							if (r.overlaps(f)) {
								overlaps = true;
								break;
							}
						}
					}

					if (bedregions == null || overlaps) {
						VCFVariant vcfVariant = new VCFVariant(line, VCFVariant.PARSE.GENOTYPES);
						if (vcfVariant.getMAF() > threshold) {
							Double Rsq = vcfVariant.getInfo().get("AR2");
							refVals.add(Rsq);
							String variant = tokens[0] + "_" + tokens[1] + "_" + tokens[2];
							allVariants.add(variant);
						}
					}

				}
				line = tf2.readLine();
				lnctr++;
				if (lnctr % 1000 == 0) {
					System.out.print(".");
				}
			}
			System.out.println();
			System.out.println(allVariants.size() + " variants loaded from: " + file);
			tf2.close();

			return new Triple<>(ref, refVals, allVariants);
		}
	}

}
