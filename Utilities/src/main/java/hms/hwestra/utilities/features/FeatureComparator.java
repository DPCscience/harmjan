/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package hms.hwestra.utilities.features;

import java.util.Comparator;

/**
 *
 * @author hwestra
 */
public class FeatureComparator implements Comparator<Feature> {

    boolean removeOverlap = false;

    public FeatureComparator(boolean removeOverlap) {
        this.removeOverlap = removeOverlap;
    }

    @Override
    public int compare(Feature obj1, Feature obj2) {
        if (obj1.equals(obj2)) {
            return 0;
        }
        if (removeOverlap) {
            if (obj1.overlaps(obj2)) {
                return 0;
            }
        }
        
        if (obj1.getChromosome().getNumber() > obj2.getChromosome().getNumber()) {
            return 1;
        } else if (obj1.getChromosome().getNumber() < obj2.getChromosome().getNumber()) {
            return -1;
        }

        if (obj1.getStart() > obj2.getStart()) {
            return 1;
        } else if (obj1.getStart() < obj2.getStart()) {
            return -1;
        }

        if (obj1.getStrand().getNumber() > obj2.getStrand().getNumber()) {
            return 1;
        } else {
            return -1;
        }

//        if (obj1.overlaps(obj2)) {
//
//            if (obj1.getStart() >= obj2.getStart() && obj1.getStop() <= obj2.getStop()) {
//                
//                
////                return 0;
//
//            } else if (obj1.getStart() <= obj2.getStart()) {
//                return -1;
//            } else {
//                return 1;
//            }
//        } else {
//        }
    }

}