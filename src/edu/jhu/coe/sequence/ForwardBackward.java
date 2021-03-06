package edu.jhu.coe.sequence;

import java.util.Arrays;

import edu.jhu.coe.math.DoubleArrays;

public class ForwardBackward {

	boolean DEBUG = true;
	int MAX_LENGTH ;
	int numStates;
	double[][] alphas;
	double[][] betas;

	int obsLength;

	double normConstant ;

	double[][] nodePotentials;

	double[] alphaScalingFactors;
	double[] betaScalingFactors;

	double[][][] edgePosteriors;
	double[][] nodePosteriors;

	int[][]  allowableForwardTransitions;
	int[][]  allowableBackwardTransitions;

	private final double[][][] edgePotentials;

	public ForwardBackward(SequenceModel seqModel) {
		numStates = seqModel.getNumLabels();
		MAX_LENGTH = seqModel.getMaximumSequenceLength();
		alphas = new double[MAX_LENGTH][numStates];
		betas = new double[MAX_LENGTH][numStates];

		alphaScalingFactors = new double[MAX_LENGTH];
		betaScalingFactors = new double[MAX_LENGTH];

		nodePotentials = new double[MAX_LENGTH][numStates];
		nodePosteriors = new double[MAX_LENGTH][numStates];
		edgePotentials = new double[MAX_LENGTH][numStates][numStates];
		edgePosteriors = new double[MAX_LENGTH][numStates][numStates];

		allowableForwardTransitions = seqModel.getAllowableForwardTransitions();
		allowableBackwardTransitions = seqModel.getAllowableBackwardTransitions();
	}

	public void setInput(SequenceInstance seqInstance) {
		this.obsLength = seqInstance.getSequenceLength();
		this.normConstant = 0.0;

		clearArrays();

		seqInstance.fillNodePotentials(nodePotentials);
		seqInstance.fillEdgePotentials(edgePotentials);

		forwardPass();
		backwardPass();
		computePosteriors();
	}

	private void clearArrays() {
		for (int i=0; i < obsLength; ++i) {
			Arrays.fill(nodePotentials[i],0.0);
			Arrays.fill(nodePosteriors[i],0.0);
		}
		
		for (int i=0; i+1 < obsLength; ++i) {
			for (int s=0; s < numStates; ++s) {
				Arrays.fill(edgePotentials[i][s],0.0);
				Arrays.fill(edgePosteriors[i][s],0.0);
			}
		}
		Arrays.fill(alphaScalingFactors,0.0);
		Arrays.fill(betaScalingFactors,0.0);
	}

	// SCALING
	public static final double SCALE = Math.exp(100);
	public static final double LOG_SCALE = Math.log(SCALE);
	// Note: e^709 is the largest double java can handle.

	private static double getScaleFactor(double logScale) {
		if (logScale == 0.0) return 1.0;
		if (logScale == 1.0) return SCALE;
		if (logScale == 2.0) return SCALE * SCALE;
		if (logScale == 3.0) return SCALE * SCALE * SCALE;
		if (logScale == -1.0) return 1.0 / SCALE;
		if (logScale == -2.0) return 1.0 / SCALE / SCALE;
		if (logScale == -3.0) return 1.0 / SCALE / SCALE / SCALE;
		return Math.pow(SCALE, logScale);
	}

	public double getLogNormalizationConstant() {
		return normConstant;
	}

	public double[][] getNodeMarginals() {
		return nodePosteriors;
	}

	public double[][][] getEdgeMarginals() {
		return edgePosteriors;
	}

	public int[] viterbiDecode() {
		int[][] backPointers = new int[obsLength][numStates];
		for (int pos=1; pos < obsLength; ++pos) {
			for (int state=0; state < numStates; ++state) {
				int argMax = -1;
				double max = Double.NEGATIVE_INFINITY;
				int[] beforeStates = allowableBackwardTransitions[state];
				for (int beforeState: beforeStates) {
					double delta = alphas[pos-1][beforeState] * edgePotentials[pos-1][beforeState][state] * nodePotentials[pos][state];
					if (delta > max) {
						argMax = beforeState;
						max = delta;
					}
				}
				backPointers[pos][state] = argMax;
			}
		}

		int[] path = new int[obsLength];
		path[obsLength-1] = DoubleArrays.argMax(alphas[obsLength-1]);
		for (int i=obsLength-2; i >= 0; --i) {
			path[i] = backPointers[i+1][path[i+1]];
		}

		return path;
	}

	public int[] nodePosteriorDecode() {
		int[] path = new int[obsLength];
		for (int i=0; i < obsLength; ++i) {
			path[i] = DoubleArrays.argMax(nodePosteriors[i]);
		}
		return path;
	}

	private void forwardPass() {
		for (int pos=0; pos < obsLength; ++pos) {
			double max = Double.NEGATIVE_INFINITY;
			if (pos==0)  {
				for (int state=0; state < numStates; ++state) {
					alphas[pos][state] = nodePotentials[pos][state];
					if (alphas[pos][state] > max)  max = alphas[pos][state];
				}
			} else {
				for (int state=0; state < numStates; ++state) {
					double alpha = 0.0;
					if (nodePotentials[pos][state] > 0) {
						int[] beforeStates = allowableBackwardTransitions[state];
						for (int beforeState: beforeStates) {
							alpha += alphas[pos-1][beforeState] * edgePotentials[pos-1][beforeState][state];
						}
						alpha *= nodePotentials[pos][state];
					}
					if (alpha > max) max = alpha;
					alphas[pos][state] = alpha;
				}
			}
			assert !Double.isInfinite(max);
			//LOG SCALE
			int logScale = 0;
			double scale = 1.0;
			while (max > SCALE) {
				max /= SCALE;
				scale *= SCALE;
				logScale += 1;
			}
			while (max > 0.0 && max < 1.0 / SCALE) {
				max *= SCALE;
				scale /= SCALE;
				logScale -= 1;
			}
			if (logScale != 0) {
				for (int label=0; label < numStates; ++label) {
					alphas[pos][label] /= scale;
				}
			}
			if (pos ==0) {
				alphaScalingFactors[pos] = logScale;
			} else {
				alphaScalingFactors[pos] = alphaScalingFactors[pos-1] + logScale;
			}
		}
	}

	private void backwardPass() {

		for (int pos=obsLength-1; pos >= 0; --pos) {
			double max = 0.0;
			if (pos  == obsLength-1) {
				for (int label=0; label < numStates; ++label) {
					betas[pos][label] = nodePotentials[pos][label];
					if (betas[pos][label] > max) max = betas[pos][label];
				}
			} else {
				for (int state =0; state < numStates; ++state) {
					double beta = 0.0;
					if (nodePotentials[pos][state] > 0.0) {
						//Loop over following States
						int[] nextStates = allowableForwardTransitions[state];
						for (int nextState: nextStates) {
							beta +=  edgePotentials[pos][state][nextState] * betas[pos+1][nextState];
						}
						beta *= nodePotentials[pos][state];
					}
					if (beta > max) max = beta;
					betas[pos][state] = beta;
				}
			}
			int logScale = 0;
			double scale = 1.0;
			while (max > SCALE) {
				max /= SCALE;
				scale *= SCALE;
				logScale += 1;
			}
			while (max > 0.0 && max < 1.0 / SCALE) {
				max *= SCALE;
				scale /= SCALE;
				logScale -= 1;
			}
			if (logScale != 0) {
				for (int label=0; label < numStates; ++label) {
					betas[pos][label] /= scale;
				}
			}
			if (pos == obsLength-1) {
				betaScalingFactors[pos] = logScale;
			}  else {
				betaScalingFactors[pos] = betaScalingFactors[pos+1] + logScale;
			}
		}
	}

	public double[][] getAlphas() {
		return alphas;
	}

	public double[][] getBetas () {
		return betas;
	}

	private void computePosteriors() {
		double z = 0.0;  // Likelihood of the sequence
		for (int state=0; state < numStates; ++state) {
			z += alphas[obsLength-1][state];
		}
		double z_scale = alphaScalingFactors[obsLength-1];

//		assert z != 0.0 : "Forward-Backward: No non-zero label sequences";

		for (int i = 0; i+1 < obsLength; i++) {
			double[] cur_betas = betas[i+1];
			double beta_scale = betaScalingFactors[i+1];
			double alpha_scale = alphaScalingFactors[i];
			double posterior_scale = alpha_scale + beta_scale - z_scale;
			double exp_scale = getScaleFactor(posterior_scale);
			for (int s = 0; s < numStates; s++) {
				int[] nextLabels = allowableForwardTransitions[s];
				double alpha_s = alphas[i][s];
				if (alpha_s == 0.0) continue;
				double nodeSum = 0.0;
				double x = alpha_s / z;
				for (int t: nextLabels) {
					double beta_t = cur_betas[t];
					if (beta_t == 0.0) continue;
					double unscaled_posterior = x * edgePotentials[i][s][t]  * beta_t ;
					double edgePosterior = unscaled_posterior * exp_scale;
					edgePosteriors[i][s][t] += edgePosterior;
					nodeSum += edgePosterior;
				}
				nodePosteriors[i][s] = nodeSum;
			}
		}

		for (int t = 0; t < numStates; t++) {
			double alpha_t = alphas[obsLength-1][t];
			if (alpha_t == 0.0) continue;
			double beta_t = betas[obsLength-1][t];
			double beta_scale = betaScalingFactors[obsLength-1];
			double alpha_scale = alphaScalingFactors[obsLength-1];
			double unscaled_posterior = alpha_t * beta_t / (z * nodePotentials[obsLength-1][t]);
			double posterior_scale = alpha_scale  + beta_scale - z_scale;
			double exp_scale = getScaleFactor(posterior_scale);
			double nodePosterior = unscaled_posterior * exp_scale;
			nodePosteriors[obsLength-1][t] = nodePosterior;
		}
		normConstant = z_scale * LOG_SCALE + Math.log(z);

		if (DEBUG) {
			probCheck();
		}
	}


	@SuppressWarnings("unused")
	private void probCheck() {
		//Test Probability
		for (int pos=0; pos < obsLength; ++pos) {
			double nodeSum = 0.0;
			double edgeSum = 0.0;
			for (int l=0; l < nodePosteriors[pos].length; ++l) nodeSum += nodePosteriors[pos][l];
			assert (Math.abs(nodeSum-1.0) < .001) : "Node Sum: " + nodeSum + " not 1.0 for " + pos;
		}
		for (int i=0; i+1 < obsLength; ++i) {
			double edgeSum = 0;
			for (int label=0; label < numStates; ++label) {				
				int[] nextLabels = allowableForwardTransitions[label];
				for(int nextLabel: nextLabels) {
					edgeSum += edgePosteriors[i][label][nextLabel];
				}
			}
			assert Math.abs(1.0-edgeSum) < .001 : "Edge Sum: " + edgeSum + " for " + obsLength;
		}
		
	}

}

