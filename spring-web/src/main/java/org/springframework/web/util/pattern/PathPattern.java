/*
 * Copyright 2002-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.util.pattern;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.server.PathContainer;
import org.springframework.http.server.PathContainer.Element;
import org.springframework.http.server.PathContainer.Separator;
import org.springframework.lang.Nullable;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;

/**
 * Representation of a parsed path pattern. Includes a chain of path elements
 * for fast matching and accumulates computed state for quick comparison of
 * patterns.
 *
 * <p>{@code PathPattern} matches URL paths using the following rules:<br>
 * <ul>
 * <li>{@code ?} matches one character</li>
 * <li>{@code *} matches zero or more characters within a path segment</li>
 * <li>{@code **} matches zero or more <em>path segments</em> until the end of the path</li>
 * <li>{@code {spring}} matches a <em>path segment</em> and captures it as a variable named "spring"</li>
 * <li>{@code {spring:[a-z]+}} matches the regexp {@code [a-z]+} as a path variable named "spring"</li>
 * <li>{@code {*spring}} matches zero or more <em>path segments</em> until the end of the path
 * and captures it as a variable named "spring"</li>
 * </ul>
 *
 * <h3>Examples</h3>
 * <ul>
 * <li>{@code /pages/t?st.html} &mdash; matches {@code /pages/test.html} but also
 * {@code /pages/tast.html} but not {@code /pages/toast.html}</li>
 * <li>{@code /resources/*.png} &mdash; matches all {@code .png} files in the
 * {@code resources} directory</li>
 * <li><code>/resources/&#42;&#42;</code> &mdash; matches all files
 * underneath the {@code /resources/} path, including {@code /resources/image.png}
 * and {@code /resources/css/spring.css}</li>
 * <li><code>/resources/{&#42;path}</code> &mdash; matches all files
 * underneath the {@code /resources/} path and captures their relative path in
 * a variable named "path"; {@code /resources/image.png} will match with
 * "spring" -> "/image.png", and {@code /resources/css/spring.css} will match
 * with "spring" -> "/css/spring.css"</li>
 * <li>{@code /resources/{filename:\\w+}.dat} will match {@code /resources/spring.dat}
 * and assign the value {@code "spring"} to the {@code filename} variable</li>
 * </ul>
 *
 * @author Andy Clement
 * @author Rossen Stoyanchev
 * @since 5.0
 * @see PathContainer
 */
public class PathPattern implements Comparable<PathPattern> {

	private final static PathContainer EMPTY_PATH = PathContainer.parsePath("");


	/** The text of the parsed pattern */
	private final String patternString;

	/** The parser used to construct this pattern */
	private final PathPatternParser parser;

	/** The separator used when parsing the pattern */
	private final char separator;

	/** If this pattern has no trailing slash, allow candidates to include one and still match successfully */
	private final boolean matchOptionalTrailingSeparator;

	/** Will this match candidates in a case sensitive way? (case sensitivity  at parse time) */
	private final boolean caseSensitive;

	/** First path element in the parsed chain of path elements for this pattern */
	@Nullable
	private final PathElement head;

	/** How many variables are captured in this pattern */
	private int capturedVariableCount;

	/**
	 * The normalized length is trying to measure the 'active' part of the pattern. It is computed
	 * by assuming all captured variables have a normalized length of 1. Effectively this means changing
	 * your variable name lengths isn't going to change the length of the active part of the pattern.
	 * Useful when comparing two patterns.
	 */
	private int normalizedLength;

	/**
	 * Does the pattern end with '&lt;separator&gt;*' 
	 */
	private boolean endsWithSeparatorWildcard = false;

	/**
	 * Score is used to quickly compare patterns. Different pattern components are given different
	 * weights. A 'lower score' is more specific. Current weights:
	 * <ul>
	 * <li>Captured variables are worth 1
	 * <li>Wildcard is worth 100
	 * </ul>
	 */
	private int score;

	/** Does the pattern end with {*...} */
	private boolean catchAll = false;


	PathPattern(String patternText, PathPatternParser parser, @Nullable PathElement head) {
		this.patternString = patternText;
		this.parser = parser;
		this.separator = parser.getSeparator();
		this.matchOptionalTrailingSeparator = parser.isMatchOptionalTrailingSeparator();
		this.caseSensitive = parser.isCaseSensitive();
		this.head = head;

		// Compute fields for fast comparison
		PathElement elem = head;
		while (elem != null) {
			this.capturedVariableCount += elem.getCaptureCount();
			this.normalizedLength += elem.getNormalizedLength();
			this.score += elem.getScore();
			if (elem instanceof CaptureTheRestPathElement || elem instanceof WildcardTheRestPathElement) {
				this.catchAll = true;
			}
			if (elem instanceof SeparatorPathElement && elem.next != null &&
					elem.next instanceof WildcardPathElement && elem.next.next == null) {
				this.endsWithSeparatorWildcard = true;
			}
			elem = elem.next;
		}
	}


	/**
	 * Return the original String that was parsed to create this PathPattern.
	 */
	public String getPatternString() {
		return this.patternString;
	}


	/**
	 * Whether this pattern matches the given path.
	 * @param pathContainer the candidate path to attempt to match against
	 * @return {@code true} if the path matches this pattern
	 */
	public boolean matches(PathContainer pathContainer) {
		if (this.head == null) {
			return !hasLength(pathContainer);
		}
		else if (!hasLength(pathContainer)) {
			if (this.head instanceof WildcardTheRestPathElement || this.head instanceof CaptureTheRestPathElement) {
				pathContainer = EMPTY_PATH; // Will allow CaptureTheRest to bind the variable to empty
			}
			else {
				return false;
			}
		}
		MatchingContext matchingContext = new MatchingContext(pathContainer, false);
		return this.head.matches(0, matchingContext);
	}

	/**
	 * Match this pattern to the given URI path and return extracted URI template
	 * variables as well as path parameters (matrix variables).
	 * @param pathContainer the candidate path to attempt to match against
	 * @return info object with the extracted variables, or {@code null} for no match
	 */
	@Nullable
	public PathMatchInfo matchAndExtract(PathContainer pathContainer) {
		if (this.head == null) {
			return hasLength(pathContainer) ? null : PathMatchInfo.EMPTY;
		}
		else if (!hasLength(pathContainer)) {
			if (this.head instanceof WildcardTheRestPathElement || this.head instanceof CaptureTheRestPathElement) {
				pathContainer = EMPTY_PATH; // Will allow CaptureTheRest to bind the variable to empty
			}
			else {
				return null;
			}
		}
		MatchingContext matchingContext = new MatchingContext(pathContainer, true);
		return this.head.matches(0, matchingContext) ? matchingContext.getPathMatchResult() : null;
	}

	/**
	 * Match the beginning of the given path and return the remaining portion
	 * not covered by this pattern. This is useful for matching nested routes
	 * where the path is matched incrementally at each level.
	 * @param pathContainer the candidate path to attempt to match against
	 * @return info object with the match result or {@code null} for no match
	 */
	@Nullable
	public PathRemainingMatchInfo matchStartOfPath(PathContainer pathContainer) {
		if (this.head == null) {
			return new PathRemainingMatchInfo(pathContainer);
		}
		else if (!hasLength(pathContainer)) {
			return null;
		}

		MatchingContext matchingContext = new MatchingContext(pathContainer, true);
		matchingContext.setMatchAllowExtraPath();
		boolean matches = this.head.matches(0, matchingContext);
		if (!matches) {
			return null;
		}
		else {
			PathRemainingMatchInfo info;
			if (matchingContext.remainingPathIndex == pathContainer.elements().size()) {
				info = new PathRemainingMatchInfo(EMPTY_PATH, matchingContext.getPathMatchResult());
			}
			else {
				info = new PathRemainingMatchInfo(pathContainer.subPath(matchingContext.remainingPathIndex),
						matchingContext.getPathMatchResult());
			}
			return info;
		}
	}

	/**
	 * Determine the pattern-mapped part for the given path.
	 * <p>For example: <ul>
	 * <li>'{@code /docs/cvs/commit.html}' and '{@code /docs/cvs/commit.html} -> ''</li>
	 * <li>'{@code /docs/*}' and '{@code /docs/cvs/commit}' -> '{@code cvs/commit}'</li>
	 * <li>'{@code /docs/cvs/*.html}' and '{@code /docs/cvs/commit.html} -> '{@code commit.html}'</li>
	 * <li>'{@code /docs/**}' and '{@code /docs/cvs/commit} -> '{@code cvs/commit}'</li>
	 * </ul>
	 * <p><b>Note:</b> Assumes that {@link #matches} returns {@code true} for
	 * the same path but does <strong>not</strong> enforce this.
	 * @param path a path that matches this pattern
	 * @return the subset of the path that is matched by pattern or "" if none
	 * of it is matched by pattern elements
	 */
	public PathContainer extractPathWithinPattern(PathContainer path) {

		// TODO: implement extractPathWithinPattern for PathContainer

		// TODO: align behavior with matchStartOfPath with regards to this:
		// As per the contract on {@link PathMatcher}, this method will trim leading/trailing
		// separators. It will also remove duplicate separators in the returned path.

		String result = extractPathWithinPattern(path.value());
		return PathContainer.parsePath(result);
	}

	private String extractPathWithinPattern(String path) {
		// assert this.matches(path)
		PathElement elem = head;
		int separatorCount = 0;
		boolean matchTheRest = false;

		// Find first path element that is pattern based
		while (elem != null) {
			if (elem instanceof SeparatorPathElement || elem instanceof CaptureTheRestPathElement ||
					elem instanceof WildcardTheRestPathElement) {
				separatorCount++;
				if (elem instanceof WildcardTheRestPathElement || elem instanceof CaptureTheRestPathElement) {
					matchTheRest = true;
				}
			}
			if (elem.getWildcardCount() != 0 || elem.getCaptureCount() != 0) {
				break;
			}
			elem = elem.next;
		}

		if (elem == null) {
			return "";  // there is no pattern mapped section
		}

		// Now separatorCount indicates how many sections of the path to skip
		char[] pathChars = path.toCharArray();
		int len = pathChars.length;
		int pos = 0;
		while (separatorCount > 0 && pos < len) {
			if (path.charAt(pos++) == separator) {
				separatorCount--;
			}
		}
		int end = len;
		// Trim trailing separators
		if (!matchTheRest) {
			while (end > 0 && path.charAt(end - 1) == separator) {
				end--;
			}
		}

		// Check if multiple separators embedded in the resulting path, if so trim them out.
		// Example: aaa////bbb//ccc/d -> aaa/bbb/ccc/d
		// The stringWithDuplicateSeparatorsRemoved is only computed if necessary
		int c = pos;
		StringBuilder stringWithDuplicateSeparatorsRemoved = null;
		while (c < end) {
			char ch = path.charAt(c);
			if (ch == separator) {
				if ((c + 1) < end && path.charAt(c + 1) == separator) {
					// multiple separators
					if (stringWithDuplicateSeparatorsRemoved == null) {
						// first time seen, need to capture all data up to this point
						stringWithDuplicateSeparatorsRemoved = new StringBuilder();
						stringWithDuplicateSeparatorsRemoved.append(path.substring(pos, c));
					}
					do {
						c++;
					} while ((c + 1) < end && path.charAt(c + 1) == separator);
				}
			}
			if (stringWithDuplicateSeparatorsRemoved != null) {
				stringWithDuplicateSeparatorsRemoved.append(ch);
			}
			c++;
		}

		if (stringWithDuplicateSeparatorsRemoved != null) {
			return stringWithDuplicateSeparatorsRemoved.toString();
		}
		return (pos == len ? "" : path.substring(pos, end));
	}


	/**
	 * Compare this pattern with a supplied pattern: return -1,0,+1 if this pattern
	 * is more specific, the same or less specific than the supplied pattern.
	 * The aim is to sort more specific patterns first.
	 */
	@Override
	public int compareTo(@Nullable PathPattern otherPattern) {
		int result = SPECIFICITY_COMPARATOR.compare(this, otherPattern);
		return (result == 0 && otherPattern != null ?
				this.patternString.compareTo(otherPattern.patternString) : result);
	}

	/**
	 * Combine this pattern with another. Currently does not produce a new PathPattern, just produces a new string.
	 */
	public PathPattern combine(PathPattern pattern2string) {
		// If one of them is empty the result is the other. If both empty the result is ""
		if (!StringUtils.hasLength(this.patternString)) {
			if (!StringUtils.hasLength(pattern2string.patternString)) {
				return parser.parse("");
			}
			else {
				return pattern2string;
			}
		}
		else if (!StringUtils.hasLength(pattern2string.patternString)) {
			return this;
		}

		// /* + /hotel => /hotel
		// /*.* + /*.html => /*.html
		// However:
		// /usr + /user => /usr/user 
		// /{foo} + /bar => /{foo}/bar
		if (!this.patternString.equals(pattern2string.patternString) && this.capturedVariableCount == 0 && 
				matches(PathContainer.parsePath(pattern2string.patternString))) {
			return pattern2string;
		}

		// /hotels/* + /booking => /hotels/booking
		// /hotels/* + booking => /hotels/booking
		if (this.endsWithSeparatorWildcard) {
			return parser.parse(concat(this.patternString.substring(0, this.patternString.length() - 2), pattern2string.patternString));
		}

		// /hotels + /booking => /hotels/booking
		// /hotels + booking => /hotels/booking
		int starDotPos1 = this.patternString.indexOf("*.");  // Are there any file prefix/suffix things to consider?
		if (this.capturedVariableCount != 0 || starDotPos1 == -1 || this.separator == '.') {
			return parser.parse(concat(this.patternString, pattern2string.patternString));
		}

		// /*.html + /hotel => /hotel.html
		// /*.html + /hotel.* => /hotel.html
		String firstExtension = this.patternString.substring(starDotPos1 + 1);  // looking for the first extension
		String p2string = pattern2string.patternString;
		int dotPos2 = p2string.indexOf('.');
		String file2 = (dotPos2 == -1 ? p2string : p2string.substring(0, dotPos2));
		String secondExtension = (dotPos2 == -1 ? "" : p2string.substring(dotPos2));
		boolean firstExtensionWild = (firstExtension.equals(".*") || firstExtension.equals(""));
		boolean secondExtensionWild = (secondExtension.equals(".*") || secondExtension.equals(""));
		if (!firstExtensionWild && !secondExtensionWild) {
			throw new IllegalArgumentException(
					"Cannot combine patterns: " + this.patternString + " and " + pattern2string);
		}
		return parser.parse(file2 + (firstExtensionWild ? secondExtension : firstExtension));
	}

	public boolean equals(Object other) {
		if (!(other instanceof PathPattern)) {
			return false;
		}
		PathPattern otherPattern = (PathPattern) other;
		return (this.patternString.equals(otherPattern.getPatternString()) &&
				this.separator == otherPattern.getSeparator() &&
				this.caseSensitive == otherPattern.caseSensitive);
	}

	public int hashCode() {
		return (this.patternString.hashCode() + this.separator) * 17 + (this.caseSensitive ? 1 : 0);
	}

	public String toString() {
		return this.patternString;
	}


	/**
	 * Holder for URI variables and path parameters (matrix variables) extracted
	 * based on the pattern for a given matched path.
	 */
	public static class PathMatchInfo {

		private static final PathMatchInfo EMPTY =
				new PathMatchInfo(Collections.emptyMap(), Collections.emptyMap());


		private final Map<String, String> uriVariables;

		private final Map<String, MultiValueMap<String, String>> matrixVariables;


		PathMatchInfo(Map<String, String> uriVars,
				@Nullable Map<String, MultiValueMap<String, String>> matrixVars) {

			this.uriVariables = Collections.unmodifiableMap(uriVars);
			this.matrixVariables = matrixVars != null ?
					Collections.unmodifiableMap(matrixVars) : Collections.emptyMap();
		}


		/**
		 * Return the extracted URI variables.
		 */
		public Map<String, String> getUriVariables() {
			return this.uriVariables;
		}

		/**
		 * Return maps of matrix variables per path segment, keyed off by URI
		 * variable name.
		 */
		public Map<String, MultiValueMap<String, String>> getMatrixVariables() {
			return this.matrixVariables;
		}

		@Override
		public String toString() {
			return "PathMatchInfo[uriVariables=" + this.uriVariables + ", " +
					"matrixVariables=" + this.matrixVariables + "]";
		}
	}

	/**
	 * Holder for the result of a match on the start of a pattern.
	 * Provides access to the remaining path not matched to the pattern as well
	 * as any variables bound in that first part that was matched.
	 */
	public static class PathRemainingMatchInfo {

		private final PathContainer pathRemaining;

		private final PathMatchInfo pathMatchInfo;


		PathRemainingMatchInfo(PathContainer pathRemaining) {
			this(pathRemaining, PathMatchInfo.EMPTY);
		}

		PathRemainingMatchInfo(PathContainer pathRemaining, PathMatchInfo pathMatchInfo) {
			this.pathRemaining = pathRemaining;
			this.pathMatchInfo = pathMatchInfo;
		}

		/**
		 * Return the part of a path that was not matched by a pattern.
		 */
		public PathContainer getPathRemaining() {
			return this.pathRemaining;
		}

		/**
		 * Return variables that were bound in the part of the path that was
		 * successfully matched or an empty map.
		 */
		public Map<String, String> getUriVariables() {
			return this.pathMatchInfo.getUriVariables();
		}

		/**
		 * Return the path parameters for each bound variable.
		 */
		public Map<String, MultiValueMap<String, String>> getMatrixVariables() {
			return this.pathMatchInfo.getMatrixVariables();
		}
	}

	int getScore() {
		return this.score;
	}

	boolean isCatchAll() {
		return this.catchAll;
	}

	/**
	 * The normalized length is trying to measure the 'active' part of the pattern. It is computed
	 * by assuming all capture variables have a normalized length of 1. Effectively this means changing
	 * your variable name lengths isn't going to change the length of the active part of the pattern.
	 * Useful when comparing two patterns.
	 */
	int getNormalizedLength() {
		return this.normalizedLength;
	}

	char getSeparator() {
		return this.separator;
	}

	int getCapturedVariableCount() {
		return this.capturedVariableCount;
	}

	String toChainString() {
		StringBuilder buf = new StringBuilder();
		PathElement pe = this.head;
		while (pe != null) {
			buf.append(pe.toString()).append(" ");
			pe = pe.next;
		}
		return buf.toString().trim();
	}

	/**
	 * @return string form of the pattern built from walking the path element chain
	 */
	String computePatternString() {
		StringBuilder buf = new StringBuilder();
		PathElement pe = this.head;
		while (pe != null) {
			buf.append(pe.getChars());
			pe = pe.next;
		}
		return buf.toString();
	}
	
	@Nullable
	PathElement getHeadSection() {
		return this.head;
	}

	/**
	 * Encapsulates context when attempting a match. Includes some fixed state like the
	 * candidate currently being considered for a match but also some accumulators for
	 * extracted variables.
	 */
	class MatchingContext {

		final PathContainer candidate;

		final List<Element> pathElements;

		final int pathLength;

		boolean isMatchStartMatching = false;

		@Nullable
		private Map<String, String> extractedUriVariables;

		@Nullable
		private Map<String, MultiValueMap<String, String>> extractedMatrixVariables;

		boolean extractingVariables;

		boolean determineRemainingPath = false;

		// if determineRemaining is true, this is set to the position in
		// the candidate where the pattern finished matching - i.e. it
		// points to the remaining path that wasn't consumed
		int remainingPathIndex;

		public MatchingContext(PathContainer pathContainer, boolean extractVariables) {
			candidate = pathContainer;
			pathElements = pathContainer.elements();
			pathLength = pathElements.size();
			this.extractingVariables = extractVariables;
		}

		public void setMatchAllowExtraPath() {
			determineRemainingPath = true;
		}

		public boolean isMatchOptionalTrailingSeparator() {
			return matchOptionalTrailingSeparator;
		}

		public void setMatchStartMatching(boolean b) {
			isMatchStartMatching = b;
		}

		public void set(String key, String value, MultiValueMap<String,String> parameters) {
			if (this.extractedUriVariables == null) {
				this.extractedUriVariables = new HashMap<>();
			}
			this.extractedUriVariables.put(key, value);

			if (!parameters.isEmpty()) {
				if (this.extractedMatrixVariables == null) {
					this.extractedMatrixVariables = new HashMap<>();
				}
				this.extractedMatrixVariables.put(key, CollectionUtils.unmodifiableMultiValueMap(parameters));
			}
		}

		public PathMatchInfo getPathMatchResult() {
			if (this.extractedUriVariables == null) {
				return PathMatchInfo.EMPTY;
			}
			else {
				return new PathMatchInfo(this.extractedUriVariables, this.extractedMatrixVariables);
			}
		}

		/**
		 * @param pathIndex possible index of a separator
		 * @return true if element at specified index is a separator
		 */
		boolean isSeparator(int pathIndex) {
			return pathElements.get(pathIndex) instanceof Separator;
		}

		/**
		 * @param pathIndex path element index
		 * @return decoded value of the specified element
		 */
		String pathElementValue(int pathIndex) {
			Element element = (pathIndex < pathLength) ? pathElements.get(pathIndex) : null;
			if (element instanceof PathContainer.PathSegment) {
				return ((PathContainer.PathSegment)element).valueToMatch();
			}
			return "";
		}
	}

	/**
	 * Join two paths together including a separator if necessary.
	 * Extraneous separators are removed (if the first path
	 * ends with one and the second path starts with one).
	 * @param path1 first path
	 * @param path2 second path
	 * @return joined path that may include separator if necessary
	 */
	private String concat(String path1, String path2) {
		boolean path1EndsWithSeparator = (path1.charAt(path1.length() - 1) == this.separator);
		boolean path2StartsWithSeparator = (path2.charAt(0) == this.separator);
		if (path1EndsWithSeparator && path2StartsWithSeparator) {
			return path1 + path2.substring(1);
		}
		else if (path1EndsWithSeparator || path2StartsWithSeparator) {
			return path1 + path2;
		}
		else {
			return path1 + this.separator + path2;
		}
	}

	/**
	 * @param container a path container
	 * @return true if the container is not null and has more than zero elements
	 */
	private boolean hasLength(@Nullable PathContainer container) {
		return container != null && container.elements().size() > 0;
	}


	public static final Comparator<PathPattern> SPECIFICITY_COMPARATOR = (p1, p2) -> {
		// Same object or null == null?
		if (p1 == p2) {
			return 0;
		}

		// null is sorted last
		if (p2 == null) {
			return -1;
		}

		// catchall patterns are sorted last. If both catchall then the
		// length is considered
		if (p1.isCatchAll()) {
			if (p2.isCatchAll()) {
				int lenDifference = p1.getNormalizedLength() - p2.getNormalizedLength();
				if (lenDifference != 0) {
					return (lenDifference < 0) ? +1 : -1;
				}
			}
			else {
				return +1;
			}
		}
		else if (p2.isCatchAll()) {
			return -1;
		}

		// This will sort such that if they differ in terms of wildcards or
		// captured variable counts, the one with the most will be sorted last
		int score = p1.getScore() - p2.getScore();
		if (score != 0) {
			return (score < 0) ? -1 : +1;
		}

		// longer is better
		int lenDifference = p1.getNormalizedLength() - p2.getNormalizedLength();
		return Integer.compare(0, lenDifference);
	};

}
