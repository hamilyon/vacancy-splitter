package ru.hh.vsplitter.split;

import com.google.common.base.CharMatcher;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.collect.EvictingQueue;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Ordering;
import com.google.common.collect.PeekingIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import ru.hh.vsplitter.classify.Classifier;
import ru.hh.vsplitter.classify.ClassifierException;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Iterables.filter;
import static ru.hh.vsplitter.split.VacancyBlock.CONDITIONS;
import static ru.hh.vsplitter.split.VacancyBlock.REQUIREMENTS;
import static ru.hh.vsplitter.split.VacancyBlock.RESPONSIBILITIES;

public class VacancySplitter  {
  private static final float THRESHOLD = 0.1f;

  private enum Language {
    ENGLISH("abcdefghijklmnopqrstuvwxyz", Arrays.asList(REQUIREMENTS, RESPONSIBILITIES)),
    RUSSIAN("абвгдеёжзийклмнопрстуфхцчшщъыьэюя", Arrays.asList(REQUIREMENTS, RESPONSIBILITIES, CONDITIONS));

    private final CharMatcher matcher;
    private final ImmutableSet<VacancyBlock> mandatoryBlocks;

    Language(String alphabet, Collection<VacancyBlock> mandatoryBlocks) {
      matcher = CharMatcher.anyOf(alphabet);
      this.mandatoryBlocks = ImmutableSet.copyOf(mandatoryBlocks);
    }

    int countIn(String text) {
      return matcher.countIn(text);
    }
  }

  private final Logger logger = LoggerFactory.getLogger(VacancySplitter.class);

  private final SAXParserFactory parserFactory = SAXParserFactory.newInstance();

  private final XmlErrorHandler errorHandler = new XmlErrorHandler(logger);
  private final TextHandler fetchTextHandler = new TextHandler();

  private final HtmlBlocksHandler ulHandler = new UlHandler();
  private final HtmlBlocksHandler sentenceHandler = new SentenceHandler();

  private final Map<String, VacancyBlock> classToBlock;
  private final ImmutableMap<Language, Classifier> classifiers;

  public VacancySplitter(Classifier engClassifier, Classifier rusClassifier, Map<String, VacancyBlock> classToBlock) {
    classifiers = ImmutableMap.of(Language.ENGLISH, engClassifier, Language.RUSSIAN, rusClassifier);
    this.classToBlock = classToBlock;
  }

  private static final Function<Collection<?>, Integer> SIZE_FUNCTION = new Function<Collection<?>, Integer>() {
    @Override
    public Integer apply(Collection<?> input) {
      return input.size();
    }
  };

  public Map<VacancyBlock, String> split(String text) throws SplitterException {
    Language language = inferLanguage(text);
    Classifier classifier = classifiers.get(language);

    Map<VacancyBlock, List<List<String>>> sequentialBlocks = parseIntoBlocks(text, ulHandler, classifier);

    if (sequentialBlocks.keySet().containsAll(language.mandatoryBlocks)) {
      Map<VacancyBlock, String> concatenated = new HashMap<>();
      for (Map.Entry<VacancyBlock, List<List<String>>> entry : sequentialBlocks.entrySet()) {
        concatenated.put(entry.getKey(), Joiner.on(' ').join(concat(entry.getValue())));
      }
      return concatenated;
    }

    sequentialBlocks = parseIntoBlocks(text, sentenceHandler, classifier);

    Map<VacancyBlock, String> concatenated = new HashMap<>();
    for (VacancyBlock block : sequentialBlocks.keySet()) {
      List<List<String>> sequence = sequentialBlocks.get(block);
      int maxSize = Ordering.natural().max(Iterables.transform(sequence, SIZE_FUNCTION));
      final int threshold = (int) (maxSize * THRESHOLD);
      concatenated.put(block, Joiner.on(' ').join(concat(filter(sequence, new Predicate<List<String>>() {
        @Override
        public boolean apply(List<String> input) {
          return input.size() > threshold;
        }
      }))));
    }
    return concatenated;
  }

  private Map<VacancyBlock, List<List<String>>> parseIntoBlocks(String xml, HtmlBlocksHandler handler, Classifier classifier) throws SplitterException {
    try (ByteArrayInputStream bos = new ByteArrayInputStream(xml.getBytes())) {
      XMLReader reader = newReader(handler);
      reader.parse(new InputSource(bos));
      return markSequentialBlocks(handler.getTextBlocks(), classifier);
    } catch (IOException | SAXException | ClassifierException e) {
      throw new SplitterException(e);
    }
  }

  private Map<VacancyBlock, List<List<String>>> markSequentialBlocks(List<String> textBlocks, Classifier classifier) throws ClassifierException {
    Map<VacancyBlock, List<List<String>>> result = new HashMap<>();
    VacancyBlock previousBlock = null;

    PeekingIterator<String> blockIterator = Iterators.peekingIterator(textBlocks.iterator());

    if (!blockIterator.hasNext()) {
      return result;
    }

    EvictingQueue<String> textQueue = EvictingQueue.create(3);
    textQueue.addAll(Arrays.asList("", "", blockIterator.peek()));

    while (blockIterator.hasNext()) {
      blockIterator.next();
      textQueue.add(blockIterator.hasNext() ? blockIterator.peek() : "");

      String left, current, right;
      Iterator<String> queueIterator = textQueue.iterator();

      left = queueIterator.next();
      current = queueIterator.next();

      left += current;
      right = current;
      right += queueIterator.next();

      VacancyBlock quorum;

      VacancyBlock leftBlock = classToBlock.get(classifier.classify(left));
      VacancyBlock currentBlock = classToBlock.get(classifier.classify(current));
      if (leftBlock == currentBlock) {
        quorum = leftBlock;
      } else {
        VacancyBlock rightBlock = classToBlock.get(classifier.classify(right));
        if (rightBlock == leftBlock) {
          quorum = rightBlock;
        } else {
          quorum = currentBlock;
        }
      }

      List<List<String>> sequence;
      if (quorum != null) {
        if (previousBlock != quorum) {
          if (!result.containsKey(quorum)) {
            sequence = new ArrayList<>();
            result.put(quorum, sequence);
          } else {
            sequence = result.get(quorum);
          }
          sequence.add(new ArrayList<String>());
        } else {
          sequence = result.get(quorum);
        }
        sequence.get(sequence.size() - 1).add(current);
      }

      previousBlock = quorum;
    }

    return result;
  }

  private Language inferLanguage(String text) throws SplitterException {
    try(ByteArrayInputStream bos = new ByteArrayInputStream(text.getBytes())) {
      newReader(fetchTextHandler).parse(new InputSource(bos));
      String lowered = fetchTextHandler.getText().toLowerCase();
      return (Language.ENGLISH.countIn(lowered) > Language.RUSSIAN.countIn(lowered)) ?
          Language.ENGLISH :
          Language.RUSSIAN;
    } catch (IOException | SAXException e) {
      throw new SplitterException(e);
    }
  }

  private XMLReader newReader(ContentHandler contentHandler) throws SplitterException {
    try {
      XMLReader reader = parserFactory.newSAXParser().getXMLReader();
      reader.setErrorHandler(errorHandler);
      reader.setContentHandler(contentHandler);
      return reader;
    } catch (ParserConfigurationException | SAXException e) {
      throw new SplitterException(e);
    }
  }
}
