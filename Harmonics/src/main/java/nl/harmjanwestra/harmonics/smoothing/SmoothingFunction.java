package nl.harmjanwestra.harmonics.smoothing;

/**
 * Created by hwestra on 6/28/15.
 */
public interface SmoothingFunction {

	double kernel(int[] data);

	double kernel(double[] data);

}
