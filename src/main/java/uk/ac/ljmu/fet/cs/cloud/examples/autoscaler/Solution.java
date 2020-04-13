package uk.ac.ljmu.fet.cs.cloud.examples.autoscaler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

import hu.mta.sztaki.lpds.cloud.simulator.iaas.IaaSService;
import hu.mta.sztaki.lpds.cloud.simulator.iaas.VirtualMachine;

/**
 * The class applies a simple threshold based scaling mechanism: it removes VMs
 * which are not utilised to a certain level; and adds VMs to the VI if most of
 * the VMs are too heavily utilised.
 * 
 * @author "Gabor Kecskemeti, Department of Computer Science, Liverpool John
 *         Moores University, (c) 2019"
 */
public class Solution extends VirtualInfrastructure {
	/**
	 * We keep track of how many times we found the last VM completely unused for an
	 * particular executable
	 */
	private final HashMap<VirtualMachine, Integer> unnecessaryHits = new HashMap<VirtualMachine, Integer>();

	/**
	 * Initialises the auto scaling mechanism
	 * 
	 * @param cloud
	 *            the physical infrastructure to use to rent the VMs from
	 */
	public Solution(final IaaSService cloud) {
		super(cloud);
	}

	@Override
	public void tick(long fires) {
		// Regular operation, the actual "autoscaler"

		// Iterating through the different application types
		Iterator<String> kinds = vmSetPerKind.keySet().iterator();
		while (kinds.hasNext()) {
			String kind = kinds.next();
			ArrayList<VirtualMachine> vmset = vmSetPerKind.get(kind);
			// Determining if we need a brand new VM for the application
			if (vmset.isEmpty()) {
				// Application has no VMs, we need at least one for each so let's create one
				requestVM(kind);
				continue;
			} else if (vmset.size() == 1) {
				final VirtualMachine onlyMachine = vmset.get(0);
//				there is only one VM left, so we will give it one hour to become active before deleting it
				if (onlyMachine.underProcessing.isEmpty() && onlyMachine.toBeAdded.isEmpty()) {
					// It has no ongoing computation
					Integer i = unnecessaryHits.get(onlyMachine);
					if (i == null) {
						unnecessaryHits.put(onlyMachine, 1);
					} else {
						i++;
						if (i < 30) {
							unnecessaryHits.put(onlyMachine, i);
						} else {
							// After an hour of disuse, we just drop the VM
							unnecessaryHits.remove(onlyMachine);
							destroyVM(onlyMachine);
							kinds.remove();
						}
					}
					// We don't need to check if we need more VMs as it has no computation
					continue;
				}
				// The VM now does some stuff now so we make sure we don't try to remove it
				// prematurely
				unnecessaryHits.remove(onlyMachine);
				// Now we allow the check if we need more VMs.
			} else {
				// remove all underutilized machines
				for (int i = 0; i < vmset.size(); i++) {
					final VirtualMachine vm = vmset.get(i);
					if (vm.underProcessing.isEmpty() && vm.toBeAdded.isEmpty()) {
						// The VM has no task on it at the moment, good candidate for deletion
						if (getHourlyUtilisationPercForVM(vm) < 0.20) {
							// The VM is idle and underutilised over the past hour so makes sense to delete
							destroyVM(vm);
							// decrement i seeing that vmset size has been decreased
							i--;
						}
					}
				}
			}
			// We need to check if we need more VMs than we have at the moment
			double subHourUtilSum = 0;
			for (VirtualMachine vm : vmset) {
				subHourUtilSum += getHourlyUtilisationPercForVM(vm);
			}

			// working out average VM utilisation of the cluster
			double averageVMUtilization = subHourUtilSum / vmset.size();
			double adjustmentRate = 0;

			System.out.println("");

			/*
			 * if cluster utilization is between set threshold values the specified
			 * adjustment rate will look to increase cluster size in order to reduce CPU
			 * Utilization back to below 60%
			 */

			// Determining adjustment rate based off utilization ranges
			System.out.println("Cluster Utilizationn: " + averageVMUtilization);
			if (averageVMUtilization > 0.60 && averageVMUtilization < 0.69) {
				// increase cluster size by 20%
//				System.out.println("Between 60 and 69");
				adjustmentRate = 1.20;
			}
			if (averageVMUtilization > 0.70 && averageVMUtilization < 0.79) {
				// increase cluster size by 40%
//				System.out.println("Between 70 and 79");
				adjustmentRate = 1.40;
			}
			if (averageVMUtilization > 0.80 && averageVMUtilization < 0.90) {
				// increase cluster size by 60%
//				System.out.println("Between 80 and 90");
				adjustmentRate = 1.60;
			}
			if (averageVMUtilization > 0.90) {
				// increase cluster size by 60%
//				System.out.println("Over 90!");
				adjustmentRate = 1.80;
			}

			if (adjustmentRate <= 0) {
//				System.out.println("CPU Utilization within 20 to 60");
				// UP scaling thresholds not exceeded utilisation is where it should be
				continue;
			}
			// calculate the amount of VMs needed to maintain attain CPU utilization level
			// below 60%
			int vmsNeeded = (int) Math.ceil(vmset.size() * adjustmentRate) - vmset.size();
			System.out.println("VMs Needed: " + vmsNeeded + " vmsize: " + vmset.size());

			// request as many VMs as needed
			for (int i = 0; i <= vmsNeeded; i++) {
				System.out.println("Requesting VM " + i);
				requestVM(kind);
			}

		}
	}
}
