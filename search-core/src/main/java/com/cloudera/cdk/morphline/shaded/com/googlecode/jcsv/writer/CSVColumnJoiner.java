/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cloudera.cdk.morphline.shaded.com.googlecode.jcsv.writer;

import com.cloudera.cdk.morphline.shaded.com.googlecode.jcsv.CSVStrategy;

/**
 * The csv column joiner receives an array of strings and joines it
 * to a single string that represents a line of the csv file.
 *
 */
public interface CSVColumnJoiner {
	/**
	 * Converts a String[] array into a single string, using the
	 * given csv strategy.
	 *
	 * @param data incoming data
	 * @param strategy the csv format descriptor
	 * @return the joined columns
	 */
	public String joinColumns(String[] data, CSVStrategy strategy);
}
