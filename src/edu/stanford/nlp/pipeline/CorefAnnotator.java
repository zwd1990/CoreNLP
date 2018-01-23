package edu.stanford.nlp.pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Locale;

import edu.stanford.nlp.coref.CorefCoreAnnotations;
import edu.stanford.nlp.coref.CorefProperties;
import edu.stanford.nlp.coref.CorefSystem;
import edu.stanford.nlp.coref.data.CorefChain;
import edu.stanford.nlp.coref.data.CorefChain.CorefMention;
import edu.stanford.nlp.coref.data.Mention;
import edu.stanford.nlp.ling.CoreAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations;
import edu.stanford.nlp.trees.TreeCoreAnnotations;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.util.IntTuple;
import edu.stanford.nlp.util.Pair;
import edu.stanford.nlp.util.PropertiesUtils;
import edu.stanford.nlp.util.logging.Redwood;

/**
 * This class adds coref information to an Annotation.
 *
 * A Map from id to CorefChain is put under the annotation
 * {@link edu.stanford.nlp.coref.CorefCoreAnnotations.CorefChainAnnotation}.
 *
 * @author heeyoung
 * @author Jason Bolton
 */
public class CorefAnnotator extends TextAnnotationCreator implements Annotator  {

  /** A logger for this class */
  private static final Redwood.RedwoodChannels log = Redwood.channels(CorefAnnotator.class);

  private final CorefSystem corefSystem;

  private boolean performMentionDetection ;
  private CorefMentionAnnotator mentionAnnotator;

  private final Properties props;

  public CorefAnnotator(Properties props) {
    this.props = props;
    try {
      // if user tries to run with coref.language = ENGLISH and coref.algorithm = hybrid, throw Exception
      // we do not support those settings at this time
      if (CorefProperties.algorithm(props).equals(CorefProperties.CorefAlgorithmType.HYBRID) &&
          CorefProperties.getLanguage(props).equals(Locale.ENGLISH)) {
        log.error("Error: coref.algorithm=hybrid is not supported for English, " +
            "please change coref.algorithm or coref.language");
        throw new RuntimeException();
      }
      // suppress
      props.setProperty("coref.printConLLLoadingMessage","false");
      corefSystem = new CorefSystem(props);
      props.remove("coref.printConLLLoadingMessage");
    } catch (Exception e) {
      log.error("Error creating CorefAnnotator...terminating pipeline construction!");
      log.error(e);
      throw new RuntimeException(e);
    }
    // unless custom mention detection is set, just use the default coref mention detector
    performMentionDetection = !PropertiesUtils.getBool(props, "coref.useCustomMentionDetection", false);
    if (performMentionDetection)
      mentionAnnotator = new CorefMentionAnnotator(props);
  }

  // flip which granularity of ner tag is primary
  public void setNamedEntityTagGranularity(Annotation annotation, String granularity) {
    List<CoreLabel> tokens = annotation.get(CoreAnnotations.TokensAnnotation.class);
    Class<? extends CoreAnnotation<String>> sourceNERTagClass;
    if (granularity.equals("fine"))
      sourceNERTagClass = CoreAnnotations.FineGrainedNamedEntityTagAnnotation.class;
    else if (granularity.equals("coarse"))
      sourceNERTagClass = CoreAnnotations.CoarseNamedEntityTagAnnotation.class;
    else
      sourceNERTagClass = CoreAnnotations.NamedEntityTagAnnotation.class;
    // switch tags
    for (CoreLabel token : tokens) {
      if (token.get(sourceNERTagClass) != null && !token.get(sourceNERTagClass).equals(""))
        token.set(CoreAnnotations.NamedEntityTagAnnotation.class, token.get(sourceNERTagClass));
    }
  }

  public boolean entityMentionCorefMentionMatch(Annotation ann, CoreMap em, Mention cm) {
    List<CoreLabel> emTokens = em.get(CoreAnnotations.TokensAnnotation.class);
    int emTokensSize = emTokens.size();
    int cmTokensSize = cm.endIndex - cm.startIndex;
    CoreMap cmSentence = ann.get(CoreAnnotations.SentencesAnnotation.class).get(cm.sentNum);
    List<CoreLabel>
        cmTokens = cmSentence.get(CoreAnnotations.TokensAnnotation.class).subList(cm.startIndex, cm.endIndex);
    if (Math.abs(cmTokensSize - emTokensSize) > 1) {
      return false;
    } else if (emTokensSize > cmTokensSize) {
      return false;
    } else if (cmTokensSize - emTokensSize == 1) {
      // look for a token mismatch
      for (int i = 0; i < emTokensSize; i++) {
        if (emTokens.get(i) != cmTokens.get(i))
          return false;
      }
      // check if last token is 's
      if (!cmTokens.get(cmTokensSize - 1).word().equals("'s"))
        return false;
      // no tokens mismatches and last token is "'s" so return true
      return true;
    } else {
      // look for a token mismatch
      for (int i = 0; i < emTokensSize; i++) {
        if (emTokens.get(i) != cmTokens.get(i)) {
          return false;
        }
      }
      // no tokens mismatch and em and cm same token length so return true
      return true;
    }
  }


  @Override
  public void annotate(Annotation annotation){
    // check if mention detection should be performed by this annotator
    // temporarily set the primary named entity tag to the coarse tag
    setNamedEntityTagGranularity(annotation, "coarse");
    if (performMentionDetection)
      mentionAnnotator.annotate(annotation);
    try {
      if (!annotation.containsKey(CoreAnnotations.SentencesAnnotation.class)) {
        log.error("this coreference resolution system requires SentencesAnnotation!");
        return;
      }

      if (hasSpeakerAnnotations(annotation)) {
        annotation.set(CoreAnnotations.UseMarkedDiscourseAnnotation.class, true);
      }

      corefSystem.annotate(annotation);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      // restore to the fine-grained
      setNamedEntityTagGranularity(annotation, "fine");
    }
    // attempt to link ner derived entity mentions to representative entity mentions
    List<Mention> documentCorefMentions =
        annotation.get(CorefCoreAnnotations.CorefMentionsAnnotation.class);
    for (CoreMap entityMention : annotation.get(CoreAnnotations.MentionsAnnotation.class)) {
      // attempt to get a corresponding coref mention, might be null in some cases (for instance "now")
      Integer cmIndex = entityMention.get(CoreAnnotations.TokensAnnotation.class).get(0).
          get(CorefCoreAnnotations.CorefMentionIndexAnnotation.class);
      Mention cm = null;
      CorefChain cmCorefChain = null;
      if (cmIndex != null) {
        cm = documentCorefMentions.get(cmIndex);
        cmCorefChain = annotation.get(CorefCoreAnnotations.CorefChainAnnotation.class).get(
            cm.corefClusterID);
      }
      // check for entity mention coref mention match
      if (cm != null && cmCorefChain != null && entityMentionCorefMentionMatch(annotation, entityMention, cm)) {
        // get representative mention
        CorefMention representativeMention = cmCorefChain.getRepresentativeMention();
        // get first token of representative mention
        CoreMap representativeMentionSentence =
            annotation.get(CoreAnnotations.SentencesAnnotation.class).get(representativeMention.sentNum-1);
        CoreLabel firstTokenOfRepresentativeMention =
            representativeMentionSentence.get(CoreAnnotations.TokensAnnotation.class).get(
                representativeMention.startIndex-1);
        // get potential corresponding entity mention
        Integer representativeEntityMentionIndex =
            firstTokenOfRepresentativeMention.get(CoreAnnotations.EntityMentionIndexAnnotation.class);
        Integer representativeCorefMentionIndex =
            firstTokenOfRepresentativeMention.get(CorefCoreAnnotations.CorefMentionIndexAnnotation.class);
        if (representativeEntityMentionIndex != null) {
          CoreMap representativeEntityMention =
              annotation.get(CoreAnnotations.MentionsAnnotation.class).get(representativeEntityMentionIndex);
          Mention representativeCorefMention =
              annotation.get(CorefCoreAnnotations.CorefMentionsAnnotation.class).get(
                  representativeCorefMentionIndex);
          if (entityMentionCorefMentionMatch(annotation, representativeEntityMention, representativeCorefMention)) {
            entityMention.set(CoreAnnotations.CanonicalEntityMentionIndexAnnotation.class,
                representativeEntityMentionIndex);
          }
        }
      }
    }
  }

  public static List<Pair<IntTuple, IntTuple>> getLinks(Map<Integer, CorefChain> result) {
    List<Pair<IntTuple, IntTuple>> links = new ArrayList<>();
    CorefChain.CorefMentionComparator comparator = new CorefChain.CorefMentionComparator();

    for (CorefChain c : result.values()) {
      List<CorefMention> s = c.getMentionsInTextualOrder();
      for (CorefMention m1 : s) {
        for (CorefMention m2 : s) {
          if (comparator.compare(m1, m2)==1) {
            links.add(new Pair<>(m1.position, m2.position));
          }
        }
      }
    }
    return links;
  }

  private static boolean hasSpeakerAnnotations(Annotation annotation) {
    for (CoreMap sentence : annotation.get(CoreAnnotations.SentencesAnnotation.class)) {
      for (CoreLabel t : sentence.get(CoreAnnotations.TokensAnnotation.class)) {
        if (t.get(CoreAnnotations.SpeakerAnnotation.class) != null) {
          return true;
        }
      }
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  @Override
  public Set<Class<? extends CoreAnnotation>> requires() {
    Set<Class<? extends CoreAnnotation>> requirements = new HashSet<>(Arrays.asList(
        CoreAnnotations.TextAnnotation.class,
        CoreAnnotations.TokensAnnotation.class,
        CoreAnnotations.CharacterOffsetBeginAnnotation.class,
        CoreAnnotations.CharacterOffsetEndAnnotation.class,
        CoreAnnotations.IndexAnnotation.class,
        CoreAnnotations.ValueAnnotation.class,
        CoreAnnotations.SentencesAnnotation.class,
        CoreAnnotations.SentenceIndexAnnotation.class,
        CoreAnnotations.PartOfSpeechAnnotation.class,
        CoreAnnotations.LemmaAnnotation.class,
        CoreAnnotations.NamedEntityTagAnnotation.class,
        CoreAnnotations.CoarseNamedEntityTagAnnotation.class,
        CoreAnnotations.FineGrainedNamedEntityTagAnnotation.class,
        SemanticGraphCoreAnnotations.BasicDependenciesAnnotation.class,
        SemanticGraphCoreAnnotations.EnhancedDependenciesAnnotation.class
        ));
    if (CorefProperties.mdType(this.props) != CorefProperties.MentionDetectionType.DEPENDENCY) {
      requirements.add(TreeCoreAnnotations.TreeAnnotation.class);
      requirements.add(CoreAnnotations.CategoryAnnotation.class);
    }
    if (!performMentionDetection)
      requirements.add(CorefCoreAnnotations.CorefMentionsAnnotation.class);
    return Collections.unmodifiableSet(requirements);
  }

  @Override
  public Set<Class<? extends CoreAnnotation>> requirementsSatisfied() {
    return Collections.singleton(CorefCoreAnnotations.CorefChainAnnotation.class);
  }

}
