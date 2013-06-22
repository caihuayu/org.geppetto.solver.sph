/*******************************************************************************
 * The MIT License (MIT)
 *
 * Copyright (c) 2011, 2013 OpenWorm.
 * http://openworm.org
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the MIT License
 * which accompanies this distribution, and is available at
 * http://opensource.org/licenses/MIT
 *
 * Contributors:
 *     	OpenWorm - http://openworm.org/people.html
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE
 * USE OR OTHER DEALINGS IN THE SOFTWARE.
 *******************************************************************************/

package org.geppetto.solver.sph.internal;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import junit.framework.Assert;

import org.geppetto.core.model.IModel;
import org.geppetto.core.model.state.StateTreeRoot;
import org.geppetto.core.model.state.visitors.RemoveTimeStepsVisitor;
import org.geppetto.core.simulation.TimeConfiguration;
import org.geppetto.model.sph.common.SPHConstants;
import org.geppetto.model.sph.services.SPHModelInterpreterService;
import org.geppetto.model.sph.x.SPHModelX;
import org.geppetto.model.sph.x.Vector3DX;
import org.geppetto.solver.sph.SPHSolverService;
import org.junit.Test;

public class PCISPHSolverBigTest {
	
	private void checkModelForOverlappingParticles(SPHModelX model, boolean expected)
	{
		int particleCount = model.getNumberOfParticles();
		
		// build a list of boundary particles - don't assume they are in any order
		List<Vector3DX> boundary = new ArrayList<Vector3DX>();
		for(int i = 0; i < particleCount; i++)
		{
			Vector3DX positionVector = (Vector3DX) model.getParticles().get(i).getPositionVector();
			
			if (positionVector.getP() == SPHConstants.BOUNDARY_TYPE){
				boundary.add(positionVector);
			}
		}
		
		// go through particles and check for freely moving particles with same coordinates as boundary
		// NOTE: this should never happen
		List<Vector3DX> overlapping = new ArrayList<Vector3DX>();
		for(int i = 0; i < particleCount; i++)
		{
			Vector3DX vector = (Vector3DX) model.getParticles().get(i).getPositionVector();
			
			// run it against all the boundary positions
			if (vector.getP() != SPHConstants.BOUNDARY_TYPE){
				for(Vector3DX v : boundary)
				{
					if(v.getX().equals(vector.getX()) && v.getY().equals(vector.getY()) && v.getZ().equals(vector.getZ()))
					{
						overlapping.add(vector);
					}
				}
			}
		}
		
		if(expected){
			Assert.assertTrue("Found no overlapping particles when they were expected.", overlapping.size() > 0);
		}
		else{
			Assert.assertTrue("Found overlapping particles when they were not expected: " + overlapping.toString(), overlapping.size() == 0);
		}
	}
	
	/*
	 * Counts how many non-boundary particles are in the model.
	 * */
	private int countNonBoundaryParticles(SPHModelX model)
	{
		int count = 0;
		for(int i = 0; i < model.getNumberOfParticles(); i++)
		{
			Vector3DX positionVector = (Vector3DX) model.getParticles().get(i).getPositionVector();
			
			if (positionVector.getP() != SPHConstants.BOUNDARY_TYPE){
				count++;
			}
		}
		
		return count;
	}
	
	/*
	 * Checks the entire StateTreeRoot for NaN values
	 * */
	private void checkStateTreeForNaN(StateTreeRoot set, boolean expected)
	{
		FindNaNVisitor findNaNVisitor=new FindNaNVisitor();
		set.apply(findNaNVisitor);
		
		if(expected)
		{
			Assert.assertTrue("No NaN values detected when expected.", findNaNVisitor.hasNaN());
		} 
		else 
		{
			Assert.assertTrue("Unexpected NaN values detected: " + findNaNVisitor.getParticleWithNaN(), !findNaNVisitor.hasNaN());
		}
	}
	
	/*
	 * Removes the first state from state tree values
	 * */
	private void removeFirstStateFromTree(StateTreeRoot set)
	{
		RemoveTimeStepsVisitor removeTimeStepVisitor= new RemoveTimeStepsVisitor(1);
		set.apply(removeTimeStepVisitor);
	}
	
	/*
	 * A test built around the original pureLiquid scene used to test the C++ version
	 */
	@Test
	public void testSolvePureLiquidScene_NoNaN() throws Exception
	{
		URL url = this.getClass().getResource("/sphModel_PureLiquid.xml");
		SPHModelInterpreterService modelInterpreter = new SPHModelInterpreterService();
		IModel model = modelInterpreter.readModel(url);
		
		// NOTE: commenting out as it takes a while for big scenes
		//checkModelForOverlappingParticles((SPHModelX)model, false);
		
		SPHSolverService solver = new SPHSolverService();
		solver.initialize(model);
		StateTreeRoot stateSet = solver.solve(new TimeConfiguration(0.1f, 1, 1));
		
		checkStateTreeForNaN(stateSet, false);
		
		Assert.assertTrue("Particle count doesn't match.", stateSet.getChildren().size() == countNonBoundaryParticles((SPHModelX)model));
	}
	
	/*
	 * Pure liquid scene with "many" liquid particles
	 * NOTE: compares results from running cycles in one go vs step by step
	 */
	@Test
	public void testSolvePureLiquid_StepByStep_VS_OneGo() throws Exception
	{
		URL url = this.getClass().getResource("/sphModel_PureLiquid.xml");
		SPHModelInterpreterService modelInterpreter = new SPHModelInterpreterService();
		IModel model = modelInterpreter.readModel(url);
		
		// check that we don't have particles with overlapping positions
		//checkModelForOverlappingParticles((SPHModelX)model, false);
		
		int cycles = 2;
		
		// run cycles one by one
		SPHSolverService solver1 = new SPHSolverService();
		solver1.initialize(model);
		StateTreeRoot stateTree1 = null;
		for(int i = 0; i < cycles; i++){
			stateTree1 = solver1.solve(new TimeConfiguration(0.1f, 1, 1));
		}
		
		// run cycles at once
		SPHSolverService solver2 = new SPHSolverService();
		solver2.initialize(model);
		StateTreeRoot stateTree2 = solver2.solve(new TimeConfiguration(0.1f, cycles, 1));
		
		//checks the trees are equivalent
		Assert.assertEquals(stateTree1.toString(),stateTree2.toString());
		
		checkStateTreeForNaN(stateTree1, false);
		checkStateTreeForNaN(stateTree2, false);
		
		Assert.assertTrue("Particle count doesn't match.", stateTree1.getChildren().size() == countNonBoundaryParticles((SPHModelX)model));
		Assert.assertTrue("Particle count doesn't match.", stateTree2.getChildren().size() == countNonBoundaryParticles((SPHModelX)model));
	}

	/*
	 * Scene with a block of elastic matter plus liquid
	 */
	@Test
	public void testSolveElastic_NoNaN() throws Exception
	{
		URL url = this.getClass().getResource("/sphModel_Elastic.xml");
		SPHModelInterpreterService modelInterpreter = new SPHModelInterpreterService();
		IModel model = modelInterpreter.readModel(url);
		
		//checkModelForOverlappingParticles((SPHModelX)model, false);

		SPHSolverService solver = new SPHSolverService();
		solver.initialize(model);
		StateTreeRoot stateSet = solver.solve(new TimeConfiguration(0.1f, 1, 1));
		
		checkStateTreeForNaN(stateSet, false);
		
		Assert.assertTrue("Particle count doesn't match.", stateSet.getChildren().size() == countNonBoundaryParticles((SPHModelX)model));
	}
	
	/*
	 * Scene with a block of elastic matter plus liquid
	 * NOTE: solves step by step - this frees memory so we can run many steps without running out of heap space
	 */
	@Test
	public void testSolveElastic_StepByStep_NoNaN() throws Exception
	{
		URL url = this.getClass().getResource("/sphModel_Elastic.xml");
		SPHModelInterpreterService modelInterpreter = new SPHModelInterpreterService();
		IModel model = modelInterpreter.readModel(url);
		
		// check that we don't have particles with overlapping positions
		//checkModelForOverlappingParticles((SPHModelX)model, false);
		
		int cycles = 2;
		
		// run cycles one by one
		SPHSolverService solver1 = new SPHSolverService();
		solver1.initialize(model);
		StateTreeRoot stateTree = null;
		for(int i = 0; i < cycles; i++){
			stateTree = solver1.solve(new TimeConfiguration(0.1f, 1, 1));
			if (i > 0) {
				removeFirstStateFromTree(stateTree);
			}
		}
		
		checkStateTreeForNaN(stateTree, false);
		
		Assert.assertTrue("Particle count doesn't match.", stateTree.getChildren().size() == countNonBoundaryParticles((SPHModelX)model));
	}
	
	/*
	 * Scene with a block of elastic matter plus liquid
	 * NOTE: compares results from running cycles in one go vs step by step
	 */
	@Test
	public void testSolveElastic_StepByStep_VS_OneGo() throws Exception
	{
		URL url = this.getClass().getResource("/sphModel_Elastic.xml");
		SPHModelInterpreterService modelInterpreter = new SPHModelInterpreterService();
		IModel model = modelInterpreter.readModel(url);
		
		// check that we don't have particles with overlapping positions
		//checkModelForOverlappingParticles((SPHModelX)model, false);
		
		int cycles = 2;
		
		// run cycles one by one
		SPHSolverService solver1 = new SPHSolverService();
		solver1.initialize(model);
		StateTreeRoot stateTree1 = null;
		for(int i = 0; i < cycles; i++){
			stateTree1 = solver1.solve(new TimeConfiguration(0.1f, 1, 1));
		}
		
		// run cycles at once
		SPHSolverService solver2 = new SPHSolverService();
		solver2.initialize(model);
		StateTreeRoot stateTree2 = solver2.solve(new TimeConfiguration(0.1f, cycles, 1));
		
		//checks the trees are equivalent
		Assert.assertEquals(stateTree1.toString(),stateTree2.toString());
		
		checkStateTreeForNaN(stateTree1, false);
		checkStateTreeForNaN(stateTree2, false);
		
		Assert.assertTrue("Particle count doesn't match.", stateTree1.getChildren().size() == countNonBoundaryParticles((SPHModelX)model));
		Assert.assertTrue("Particle count doesn't match.", stateTree2.getChildren().size() == countNonBoundaryParticles((SPHModelX)model));
	}
}