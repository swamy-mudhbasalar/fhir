//    Copyright 2018 Google Inc.
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//        https://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.

package com.google.fhir.stu3;

import com.google.common.base.CaseFormat;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.MoreCollectors;
import com.google.fhir.common.FhirVersion;
import com.google.fhir.common.ProtoUtils;
import com.google.fhir.proto.Annotations;
import com.google.fhir.proto.PackageInfo;
import com.google.fhir.proto.ProtoGeneratorAnnotations;
import com.google.fhir.r4.proto.BindingStrengthCode;
import com.google.fhir.r4.proto.Canonical;
import com.google.fhir.r4.proto.CodeableConcept;
import com.google.fhir.r4.proto.Coding;
import com.google.fhir.r4.proto.ElementDefinition;
import com.google.fhir.r4.proto.ExtensionContextTypeCode;
import com.google.fhir.r4.proto.StructureDefinition;
import com.google.fhir.r4.proto.StructureDefinitionKindCode;
import com.google.fhir.r4.proto.TypeDerivationRuleCode;
import com.google.fhir.r4.proto.Uri;
import com.google.fhir.stu3.proto.CodingWithFixedSystem;
import com.google.fhir.stu3.proto.ElementDefinitionExplicitTypeName;
import com.google.fhir.stu3.proto.Instant;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProtoOrBuilder;
import com.google.protobuf.DescriptorProtos.FileOptions;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Message;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** A class which turns FHIR StructureDefinitions into protocol messages. */
// TODO: Move a bunch of the public static methods into ProtoGeneratorUtils.
public class ProtoGenerator {

  // The path for annotation definition of proto options.
  static final String ANNOTATION_PATH = "proto";

  // Map of time-like primitive type ids to supported granularity
  private static final ImmutableMap<String, List<String>> TIME_LIKE_PRECISION_MAP =
      ImmutableMap.of(
          "date", ImmutableList.of("YEAR", "MONTH", "DAY"),
          "dateTime",
              ImmutableList.of("YEAR", "MONTH", "DAY", "SECOND", "MILLISECOND", "MICROSECOND"),
          "instant", ImmutableList.of("SECOND", "MILLISECOND", "MICROSECOND"),
          "time", ImmutableList.of("SECOND", "MILLISECOND", "MICROSECOND"));
  private static final ImmutableSet<String> TYPES_WITH_TIMEZONE =
      ImmutableSet.of("date", "dateTime", "instant");

  // Certain field names are reserved symbols in various languages.
  private static final ImmutableSet<String> RESERVED_FIELD_NAMES =
      ImmutableSet.of("assert", "for", "hasAnswer", "package", "string", "class");

  private static final EnumDescriptorProto PRECISION_ENUM =
      EnumDescriptorProto.newBuilder()
          .setName("Precision")
          .addValue(
              EnumValueDescriptorProto.newBuilder()
                  .setName("PRECISION_UNSPECIFIED")
                  .setNumber(0)
                  .build())
          .build();

  private static final FieldDescriptorProto TIMEZONE_FIELD =
      FieldDescriptorProto.newBuilder()
          .setName("timezone")
          .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
          .setType(FieldDescriptorProto.Type.TYPE_STRING)
          .setNumber(2)
          .build();

  private static final ImmutableMap<String, FieldDescriptorProto.Type> PRIMITIVE_TYPE_OVERRIDES =
      ImmutableMap.of(
          "base64Binary", FieldDescriptorProto.Type.TYPE_BYTES,
          "boolean", FieldDescriptorProto.Type.TYPE_BOOL,
          "integer", FieldDescriptorProto.Type.TYPE_SINT32,
          "positiveInt", FieldDescriptorProto.Type.TYPE_UINT32,
          "unsignedInt", FieldDescriptorProto.Type.TYPE_UINT32);

  // FHIR elements may have core constraint definitions that do not add
  // value to protocol buffers, so we exclude them.
  private static final ImmutableSet<String> EXCLUDED_FHIR_CONSTRAINTS =
      ImmutableSet.of("hasValue() | (children().count() > id.count())");

  // Should we use custom types for constrained references?
  private static final boolean USE_TYPED_REFERENCES = false;

  // Mapping from urls for StructureDefinition to data about that StructureDefinition.
  private final ImmutableMap<String, StructureDefinitionData> structDefDataByUrl;
  // Mapping from id to StructureDefinition data about all known base (i.e., not profile) types.
  private final ImmutableMap<String, StructureDefinition> baseStructDefsById;
  // Mapping from ValueSet url to Descriptor for the message type it should be inlined as.
  private final ImmutableMap<String, Descriptor> valueSetTypesByUrl;

  private final ImmutableMap<String, Set<String>> coreTypeDefinitionsByFile;

  private static Set<String> getTypesDefinedInFile(FileDescriptor file) {
    return file.getMessageTypes().stream()
        .map(desc -> desc.getFullName())
        .collect(Collectors.toSet());
  }

  // The package to write new protos to.
  private final PackageInfo packageInfo;
  // The fhir version to use (e.g., DSTU2, STU3, R4).
  // This determines which core dependencies (e.g., datatypes.proto) to use.
  private final FhirVersion fhirVersion;

  private static class StructureDefinitionData {
    final StructureDefinition structDef;
    final String inlineType;
    final String protoPackage;

    StructureDefinitionData(StructureDefinition structDef, String inlineType, String protoPackage) {
      this.structDef = structDef;
      this.inlineType = inlineType;
      this.protoPackage = protoPackage;
    }
  }

  private static class QualifiedType {
    final String type;
    final String packageName;

    QualifiedType(String type, String packageName) {
      this.type = type;
      this.packageName = packageName;
    }

    String toQualifiedTypeString() {
      return "." + packageName + "." + type;
    }
  }

  // Token in a id string.  The sequence of tokens forms a heirarchical relationship, where each
  // dot-delimited token is of the form pathpart:slicename/reslicename.
  // See https://www.hl7.org/fhir/elementdefinition.html#id
  private static class IdToken {
    final String pathpart;
    final boolean isChoiceType;
    final String slicename;
    final String reslice;

    private IdToken(String pathpart, boolean isChoiceType, String slicename, String reslice) {
      this.pathpart = pathpart;
      this.isChoiceType = isChoiceType;
      this.slicename = slicename;
      this.reslice = reslice;
    }

    static IdToken fromTokenString(String tokenString) {
      List<String> colonSplit = Splitter.on(':').splitToList(tokenString);
      String pathpart = colonSplit.get(0);
      boolean isChoiceType = pathpart.endsWith("[x]");
      if (isChoiceType) {
        pathpart = pathpart.substring(0, pathpart.length() - "[x]".length());
      }
      if (colonSplit.size() == 1) {
        return new IdToken(pathpart, isChoiceType, null, null);
      }
      if (colonSplit.size() != 2) {
        throw new IllegalArgumentException("Bad token string: " + tokenString);
      }
      List<String> slashSplit = Splitter.on('/').splitToList(colonSplit.get(1));
      if (slashSplit.size() == 1) {
        return new IdToken(pathpart, isChoiceType, slashSplit.get(0), null);
      }
      if (slashSplit.size() == 2) {
        return new IdToken(pathpart, isChoiceType, slashSplit.get(0), slashSplit.get(1));
      }
      throw new IllegalArgumentException("Bad token string: " + tokenString);
    }
  }

  private static IdToken lastIdToken(String idString) {
    List<String> tokenStrings = Splitter.on('.').splitToList(idString);
    return IdToken.fromTokenString(Iterables.getLast(tokenStrings));
  }

  public ProtoGenerator(
      PackageInfo packageInfo, ImmutableMap<StructureDefinition, String> knownTypes) {
    this.packageInfo = packageInfo;
    this.fhirVersion = FhirVersion.fromAnnotation(packageInfo.getFhirVersion());

    // TODO: Do this with ValueSet resources once we have them.
    ImmutableMap.Builder<String, Descriptor> valueSetTypesByUrlBuilder =
        new ImmutableMap.Builder<>();
    for (FileDescriptor desc : fhirVersion.codeTypeList) {
      valueSetTypesByUrlBuilder.putAll(loadCodeTypesFromFile(desc));
    }
    this.valueSetTypesByUrl = valueSetTypesByUrlBuilder.build();

    ImmutableMap.Builder<String, Set<String>> coreTypeBuilder = new ImmutableMap.Builder<>();
    for (Map.Entry<String, FileDescriptor> entry : fhirVersion.coreTypeMap.entrySet()) {
      coreTypeBuilder.put(entry.getKey(), getTypesDefinedInFile(entry.getValue()));
    }
    this.coreTypeDefinitionsByFile = coreTypeBuilder.build();

    Map<String, StructureDefinitionData> mutableStructDefDataByUrl = new HashMap<>();
    for (Map.Entry<StructureDefinition, String> knownType : knownTypes.entrySet()) {
      StructureDefinition def = knownType.getKey();
      String protoPackage = knownType.getValue();
      String url = def.getUrl().getValue();
      if (url.isEmpty()) {
        throw new IllegalArgumentException(
            "Invalid FHIR structure definition: " + def.getId().getValue() + " has no url");
      }
      boolean isSimpleExtensionProfile =
          isExtensionProfile(def)
              && isSimpleInternalExtension(
                  def.getSnapshot().getElement(0), def.getSnapshot().getElementList());
      String inlineType;
      String structDefPackage;
      if (isSimpleExtensionProfile) {
        QualifiedType qualifiedType = getSimpleExtensionDefinitionType(def);
        inlineType = qualifiedType.type;
        structDefPackage = qualifiedType.packageName;
      } else {
        inlineType = getTypeName(def);
        structDefPackage = protoPackage;
      }

      StructureDefinitionData structDefData =
          new StructureDefinitionData(def, inlineType, structDefPackage);
      mutableStructDefDataByUrl.put(def.getUrl().getValue(), structDefData);
    }
    this.structDefDataByUrl = ImmutableMap.copyOf(mutableStructDefDataByUrl);

    // Used to ensure each structure definition is unique by url.
    final Set<String> knownUrls = new HashSet<>();

    this.baseStructDefsById =
        knownTypes.keySet().stream()
            .filter(
                def -> def.getDerivation().getValue() != TypeDerivationRuleCode.Value.CONSTRAINT)
            .filter(def -> knownUrls.add(def.getUrl().getValue()))
            .collect(ImmutableMap.toImmutableMap(def -> def.getId().getValue(), def -> def));
  }

  static Map<String, Descriptor> loadCodeTypesFromFile(FileDescriptor file) {
    return file.getMessageTypes().stream()
        .filter(d -> d.getOptions().hasExtension(Annotations.fhirValuesetUrl))
        .collect(
            Collectors.toMap(
                d -> d.getOptions().getExtension(Annotations.fhirValuesetUrl), d -> d));
  }

  // Map from StructureDefinition url to explicit renaming for the type that should be generated.
  // This is necessary for cases where the generated name type is problematic, e.g., when two
  // generated name types collide, or just to provide nicer names.
  private static final ImmutableMap<String, String> STRUCTURE_DEFINITION_RENAMINGS =
      new ImmutableMap.Builder<String, String>()
          .put("http://hl7.org/fhir/StructureDefinition/valueset-reference", "ValueSetReference")
          .put(
              "http://hl7.org/fhir/StructureDefinition/codesystem-reference", "CodeSystemReference")
          .put(
              "http://hl7.org/fhir/StructureDefinition/request-statusReason",
              "StatusReasonExtension")
          .put(
              "http://hl7.org/fhir/StructureDefinition/workflow-relatedArtifact",
              "RelatedArtifactExtension")
          .put("http://hl7.org/fhir/StructureDefinition/event-location", "LocationExtension")
          .put(
              "http://hl7.org/fhir/StructureDefinition/workflow-episodeOfCare",
              "EpisodeOfCareExtension")
          .put(
              "http://hl7.org/fhir/StructureDefinition/workflow-researchStudy",
              "ResearchStudyExtension")
          .put(
              "http://hl7.org/fhir/us/core/StructureDefinition/us-core-condition",
              "UsCoreCondition")
          .put(
              "http://hl7.org/fhir/us/core/StructureDefinition/us-core-direct", "UsCoreDirectEmail")
          // https://gforge.hl7.org/gf/project/fhir/tracker/?action=TrackerItemEdit&tracker_item_id=22531
          .put("http://hl7.org/fhir/StructureDefinition/hdlcholesterol", "HdlCholesterol")
          .put("http://hl7.org/fhir/StructureDefinition/ldlcholesterol", "LdlCholesterol")
          .put("http://hl7.org/fhir/StructureDefinition/lipidprofile", "LipidProfile")
          .put("http://hl7.org/fhir/StructureDefinition/cholesterol", "Cholesterol")
          .put("http://hl7.org/fhir/StructureDefinition/triglyceride", "Triglyceride")
          .build();

  // Given a structure definition, gets the name of the top-level message that will be generated.
  public String getTypeName(StructureDefinition def) {
    return getTypeName(def, packageInfo.getFhirVersion());
  }

  // Given a structure definition, gets the name of the top-level message that will be generated.
  public static String getTypeName(StructureDefinition def, Annotations.FhirVersion version) {
    if (STRUCTURE_DEFINITION_RENAMINGS.containsKey(def.getUrl().getValue())) {
      return STRUCTURE_DEFINITION_RENAMINGS.get(def.getUrl().getValue());
    }
    boolean useProfileTypeName =
        useLegacyTypeNaming(version) ? isExtensionProfile(def) : isProfile(def);
    return useProfileTypeName ? getProfileTypeName(def) : getTypeNameFromId(def.getId().getValue());
  }

  // Given an id string, gets the name of the top-level message that will be generated.
  public static String getTypeNameFromId(String id) {
    return toFieldTypeCase(id);
  }

  /**
   * Generate a proto descriptor from a StructureDefinition, using the snapshot form of the
   * definition. For a more elaborate discussion of these versions, see
   * https://www.hl7.org/fhir/structuredefinition.html.
   */
  public DescriptorProto generateProto(StructureDefinition def) {
    def = reconcileSnapshotAndDifferential(def);

    // Make sure the package the proto declared in is the same as it will be generated in.
    StructureDefinitionData structDefData = structDefDataByUrl.get(def.getUrl().getValue());
    if (structDefData == null) {
      throw new IllegalArgumentException(
          "No StructureDefinition data found for: " + def.getUrl().getValue());
    }
    if (!structDefData.protoPackage.equals(packageInfo.getProtoPackage())
        && !isSingleTypedExtensionDefinition(structDefData.structDef)) {
      throw new IllegalArgumentException(
          "Inconsistent package name for "
              + def.getUrl().getValue()
              + ".  Registered in --known_types in "
              + structDefData.protoPackage
              + " but being generated in "
              + packageInfo.getProtoPackage());
    }

    List<ElementDefinition> elementList = def.getSnapshot().getElementList();
    ElementDefinition root = elementList.get(0);

    boolean isPrimitive =
        def.getKind().getValue() == StructureDefinitionKindCode.Value.PRIMITIVE_TYPE;

    // Build a top-level message description.
    StringBuilder comment =
        new StringBuilder()
            .append("Auto-generated from StructureDefinition for ")
            .append(def.getName().getValue());
    if (def.getMeta().hasLastUpdated()) {
      comment
          .append(", last updated ")
          .append(
              new InstantWrapper(
                  ProtoUtils.fieldWiseCopy(def.getMeta().getLastUpdated(), Instant.newBuilder())
                      .build()));
    }
    comment.append(".");
    if (root.hasShort()) {
      String shortString = root.getShort().getValue();
      if (!shortString.endsWith(".")) {
        shortString += ".";
      }
      comment.append("\n").append(shortString.replaceAll("[\\n\\r]", "\n"));
    }
    comment.append("\nSee ").append(def.getUrl().getValue());

    // Add message-level annotations.
    DescriptorProto.Builder builder = DescriptorProto.newBuilder();
    MessageOptions.Builder optionsBuilder =
        MessageOptions.newBuilder()
            .setExtension(
                Annotations.structureDefinitionKind,
                Annotations.StructureDefinitionKindValue.valueOf(
                    "KIND_" + def.getKind().getValue()))
            .setExtension(ProtoGeneratorAnnotations.messageDescription, comment.toString())
            .setExtension(Annotations.fhirStructureDefinitionUrl, def.getUrl().getValue());
    if (def.getAbstract().getValue()) {
      optionsBuilder.setExtension(Annotations.isAbstractType, def.getAbstract().getValue());
    }
    builder.setOptions(optionsBuilder);

    // If this is a primitive type, generate the value field first.
    if (isPrimitive) {
      generatePrimitiveValue(def, builder);
    }

    builder = generateMessage(root, elementList, builder).toBuilder();

    if (isProfile(def)) {
      String name = getTypeName(def);
      // This is a profile on a pre-existing type.
      // Make sure any nested subtypes use the profile name, not the base name
      replaceType(builder, def.getType().getValue(), name);
      StructureDefinition defInChain = def;
      while (isProfile(defInChain)) {
        String baseUrl = defInChain.getBaseDefinition().getValue();
        builder
            .setName(name)
            .getOptionsBuilder()
            .addExtension(Annotations.fhirProfileBase, baseUrl);
        defInChain = structDefDataByUrl.get(baseUrl).structDef;
      }
    }
    return builder.build();
  }

  private static boolean isProfile(StructureDefinition def) {
    return def.getDerivation().getValue() == TypeDerivationRuleCode.Value.CONSTRAINT;
  }

  /** Generate a .proto file descriptor from a list of StructureDefinitions. */
  public FileDescriptorProto generateFileDescriptor(List<StructureDefinition> defs) {
    FileDescriptorProto.Builder builder = FileDescriptorProto.newBuilder();
    builder.setPackage(packageInfo.getProtoPackage()).setSyntax("proto3");
    FileOptions.Builder options = FileOptions.newBuilder();
    if (!packageInfo.getJavaProtoPackage().isEmpty()) {
      options.setJavaPackage(packageInfo.getJavaProtoPackage()).setJavaMultipleFiles(true);
    }
    if (!packageInfo.getGoProtoPackage().isEmpty()) {
      options.setGoPackage(packageInfo.getGoProtoPackage());
    }
    options.setExtension(Annotations.fhirVersion, fhirVersion.toAnnotation());
    builder.setOptions(options);
    for (StructureDefinition def : defs) {
      DescriptorProto proto = generateProto(def);
      builder.addMessageType(proto);
    }
    // Add imports. Annotations is always needed.
    builder.addDependency(new File(ANNOTATION_PATH, "annotations.proto").toString());
    // Add the remaining FHIR dependencies if the file uses a type from the FHIR dep, but does not
    // define a type from that dep.
    for (Map.Entry<String, Set<String>> entry : coreTypeDefinitionsByFile.entrySet()) {
      String filename = entry.getKey();
      Set<String> types = entry.getValue();

      if (needsDep(builder, types)) {
        builder.addDependency(new File(fhirVersion.coreProtoImportRoot, filename).toString());
      }
    }

    return builder.build();
  }

  // Returns true if the file proto uses a type from a set of types, but does not define it.
  private boolean needsDep(FileDescriptorProtoOrBuilder fileProto, Set<String> types) {
    for (DescriptorProto descriptor : fileProto.getMessageTypeList()) {
      if (types.contains(fhirVersion.coreProtoPackage + "." + descriptor.getName())
          && !descriptor.getName().equals("RelatedArtifact")) {
        // This file defines a type from the set.  It can't depend on itself.
        // TODO: We don't pay attention to RelatedArtifact because there's an extension
        // with that name.  This is a hack, we should do something cleverer.
        return false;
      }
    }
    for (DescriptorProto proto : fileProto.getMessageTypeList()) {
      if (usesTypeFromSet(proto, types)) {
        return true;
      }
    }
    return false;
  }

  // Returns true if the file proto uses a type from a set of types.
  private boolean usesTypeFromSet(DescriptorProto proto, Set<String> types) {
    for (FieldDescriptorProto field : proto.getFieldList()) {
      // Drop leading dot before checking field type.
      if (!field.getTypeName().isEmpty() && types.contains(field.getTypeName().substring(1))) {
        return true;
      }
      for (DescriptorProto nested : proto.getNestedTypeList()) {
        if (usesTypeFromSet(nested, types)) {
          return true;
        }
      }
    }
    return false;
  }

  // Does a global find-and-replace of a given token in a message namespace.
  // This is necessary for profiles, since the StructureDefinition uses the base Resource name
  // throughout, but we wish to use the Profiled resource name.
  // So, for instance, for a profile MyProfiledResource on MyResource, this would be used to turn
  // some.package.MyResource.Submessage into some.package.MyProfiledResource.Submessage
  private void replaceType(DescriptorProto.Builder protoBuilder, String from, String to) {
    String fromWithDots = "." + from + ".";
    String toWithDots = "." + to + ".";
    for (FieldDescriptorProto.Builder field : protoBuilder.getFieldBuilderList()) {
      if (field.getTypeName().contains(fromWithDots)) {
        field.setTypeName(field.getTypeName().replaceFirst(fromWithDots, toWithDots));
      }
    }
    for (DescriptorProto.Builder nested : protoBuilder.getNestedTypeBuilderList()) {
      replaceType(nested, from, to);
    }
  }

  /**
   * Returns a version of the passed-in FileDescriptor that contains a ContainedResource message,
   * which contains only the resource types present in the generated FileDescriptorProto.
   */
  public FileDescriptorProto addContainedResource(FileDescriptorProto fileDescriptor) {
    DescriptorProto.Builder contained =
        DescriptorProto.newBuilder()
            .setName("ContainedResource")
            .addOneofDecl(OneofDescriptorProto.newBuilder().setName("oneof_resource"));
    if (packageInfo.getProtoPackage().equals(fhirVersion.coreProtoPackage)) {
      // When generating contained resources for the core type (resources.proto),
      // just iterate through all the non-abstract types, assigning tag numbers as you go
      int tagNumber = 1;
      for (DescriptorProto type : fileDescriptor.getMessageTypeList()) {
        if (!type.getOptions().getExtension(Annotations.isAbstractType)) {
          contained.addField(
              FieldDescriptorProto.newBuilder()
                  .setName(CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, type.getName()))
                  .setNumber(tagNumber++)
                  .setTypeName(type.getName())
                  .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                  .setOneofIndex(0 /* the oneof_resource */)
                  .build());
        }
      }
    } else {
      // For derived contained resources, make sure to keep the tag numbers from the base file for
      // resources that keep the same name.
      // In other words, if there is a profiled resource called "Patient", it will keep the tag
      // number of the field in the base contained resources for Patient.
      // Resources with no exact name matches are assigned field numbers that are greater than
      // the max used by the base ContainedResource.
      Descriptor baseContainedResources =
          fhirVersion.coreTypeMap.get("resources.proto").findMessageTypeByName("ContainedResource");
      List<String> resourcesToInclude =
          fileDescriptor.getMessageTypeList().stream()
              .filter(desc -> !desc.getOptions().getExtension(Annotations.isAbstractType))
              .map(desc -> desc.getName())
              .collect(Collectors.toList());
      for (FieldDescriptor field : baseContainedResources.getFields()) {
        String typename = field.getMessageType().getName();
        if (resourcesToInclude.contains(typename)) {
          contained.addField(field.toProto().toBuilder().setTypeName(typename));
          resourcesToInclude.remove(typename);
        }
      }
      int tagNumber = Iterables.getLast(baseContainedResources.getFields()).getNumber() + 1;
      for (String resourceType : resourcesToInclude) {
        contained.addField(
            FieldDescriptorProto.newBuilder()
                .setName(CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, resourceType))
                .setNumber(tagNumber++)
                .setTypeName(resourceType)
                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                .setOneofIndex(0 /* the oneof_resource */)
                .build());
      }
    }

    return fileDescriptor.toBuilder().addMessageType(contained).build();
  }

  private DescriptorProto generateMessage(
      ElementDefinition currentElement,
      List<ElementDefinition> elementList,
      DescriptorProto.Builder builder) {
    // Get the name of this message.
    List<String> messageNameParts =
        Splitter.on('.').splitToList(getContainerType(currentElement, elementList));
    String messageName = messageNameParts.get(messageNameParts.size() - 1);
    builder.setName(messageName);

    // When generating a descriptor for a primitive type, the value part may already be present.
    int nextTag = builder.getFieldCount() + 1;

    // Some repeated fields can have profiled elements in them, that get inlined as fields.
    // The most common case of this is typed extensions.  We defer adding these to the end of the
    // message, so that non-profiled messages will be binary compatiple with this proto.
    // Note that the inverse is not true - loading a profiled message bytes into the non-profiled
    // will result in the data in the typed fields being dropped.
    List<ElementDefinition> deferredElements = new ArrayList<>();

    // Loop over the direct children of this element.
    for (ElementDefinition element : getDirectChildren(currentElement, elementList)) {
      if (element.getTypeCount() == 1 && element.getType(0).getCode().getValue().isEmpty()) {
        // This is a primitive field.  Skip it, as primitive fields are handled specially.
        continue;
      }

      // Per spec, the fixed Extension.url on a top-level extension must match the
      // StructureDefinition url.  Since that is already added to the message via the
      // fhir_structure_definition_url, we can skip over it here.
      if (element.getBase().getPath().getValue().equals("Extension.url")
          && element.getFixed().hasUri()) {
        continue;
      }

      if (!isChoiceType(element) && !isSingleType(element)) {
        throw new IllegalArgumentException(
            "Illegal field has multiple types but is not a Choice Type:\n" + element);
      }

      if (lastIdToken(element.getId().getValue()).slicename != null) {
        // This is a slice.  Defer this field until the end of the message, to keep base field
        // numbers consistent across profiles.
        deferredElements.add(element);
      } else {
        buildAndAddField(element, elementList, nextTag++, builder);
      }
    }

    for (ElementDefinition deferredElement : deferredElements) {
      // Currently we only support slicing for Extensions and Codings
      if (isElementSupportedForSlicing(deferredElement)) {
        buildAndAddField(deferredElement, elementList, nextTag++, builder);
      } else {
        builder
            .addFieldBuilder()
            .setNumber(nextTag)
            .getOptionsBuilder()
            .setExtension(
                ProtoGeneratorAnnotations.reservedReason,
                "field "
                    + nextTag
                    + " reserved for "
                    + deferredElement.getId().getValue()
                    + " which uses an unsupported slicing on "
                    + deferredElement.getType(0).getCode().getValue());
        nextTag++;
      }
    }
    return builder.build();
  }

  private void buildAndAddField(
      ElementDefinition element,
      List<ElementDefinition> elementList,
      int tag,
      DescriptorProto.Builder builder) {
    // Generate the field. If this field doesn't actually exist in this version of the
    // message, for example, the max attribute is 0, buildField returns null and no field
    // should be added.
    Optional<FieldDescriptorProto> fieldOptional = buildField(element, elementList, tag);
    if (fieldOptional.isPresent()) {
      FieldDescriptorProto field = fieldOptional.get();
      Optional<DescriptorProto> optionalNestedType =
          buildNestedTypeIfNeeded(element, elementList, field);
      if (optionalNestedType.isPresent()) {
        builder.addNestedType(optionalNestedType.get());
        // The nested type is defined in the local package, so replace the core FHIR package
        // with the local packageName in the field type.
        field =
            field.toBuilder()
                .setTypeName(
                    field
                        .getTypeName()
                        .replace(fhirVersion.coreProtoPackage, packageInfo.getProtoPackage()))
                .build();
      }
      builder.addField(field);
    } else if (!element.getPath().getValue().equals("Extension.extension")
        && !element.getPath().getValue().equals("Extension.value[x]")) {
      // Don't bother adding reserved messages for Extension.extension or Extension.value[x]
      // since that's part of the extension definition, and adds a lot of unhelpful noise.
      builder
          .addFieldBuilder()
          .setNumber(tag)
          .getOptionsBuilder()
          .setExtension(
              ProtoGeneratorAnnotations.reservedReason,
              element.getPath().getValue() + " not present on profile.");
    }
  }

  private Optional<DescriptorProto> buildNestedTypeIfNeeded(
      ElementDefinition element, List<ElementDefinition> elementList, FieldDescriptorProto field) {
    Optional<DescriptorProto> choiceType = getChoiceTypeIfRequired(element, elementList, field);
    if (choiceType.isPresent()) {
      return Optional.of(choiceType.get());
    }
    if (element.getTypeCount() != 1) {
      return Optional.empty();
    }
    Optional<DescriptorProto> codingMessageWithFixedSystem =
        makeStandaloneCodingMessageWithFixedSystemIfRequired(element, elementList);
    if (codingMessageWithFixedSystem.isPresent()) {
      return codingMessageWithFixedSystem;
    }

    // If this is a container type, or a complex internal extension, define the inner message.
    // If this is a CodeableConcept, check for fixed coding slices.  Normally we don't add a
    // message for CodeableConcept because it's defined as a datatype, but if there are slices
    // on it we need to generate a custom version.
    if (isContainer(element) || isComplexInternalExtension(element, elementList)) {
      return Optional.of(generateMessage(element, elementList, DescriptorProto.newBuilder()));
    } else if (element.getTypeCount() == 1
        && element.getType(0).getCode().getValue().equals("CodeableConcept")) {
      List<ElementDefinition> codingSlices =
          getDirectChildren(element, elementList).stream()
              .filter(candidateElement -> candidateElement.hasSliceName())
              .collect(Collectors.toList());
      if (!codingSlices.isEmpty()) {
        return Optional.of(addProfiledCodeableConcept(element, elementList, codingSlices));
      }
    }
    return Optional.empty();
  }

  // Generates the nested type descriptor proto for a choice type if required.
  private Optional<DescriptorProto> getChoiceTypeIfRequired(
      ElementDefinition element, List<ElementDefinition> elementList, FieldDescriptorProto field) {
    if (isChoiceTypeExtension(element, elementList)) {
      DescriptorProto choiceType =
          makeChoiceType(getExtensionValueElement(element, elementList), elementList, field);
      // Note that we make the choice time from the "value" element that is a child of this element,
      // but use THIS element to get the name of the choice type message.
      // This is consistent with the philosophy of inlining extension types by their url name,
      // rather than the "value" field of the extension.
      String containerType = getContainerType(element, elementList);
      return Optional.of(
          choiceType.toBuilder()
              .setName(containerType.substring(containerType.lastIndexOf('.') + 1))
              .build());
    }
    Optional<ElementDefinition> choiceTypeBase = getChoiceTypeBase(element);
    if (choiceTypeBase.isPresent()) {
      List<ElementDefinition.TypeRef> baseTypes = choiceTypeBase.get().getTypeList();
      Map<String, Integer> baseTypesToIndex = new HashMap<>();
      for (int i = 0; i < baseTypes.size(); i++) {
        String code = baseTypes.get(i).getCode().getValue();
        // Only add each code type once.  This is only relevant for references, which can appear
        // multiple times.
        if (!baseTypesToIndex.containsKey(code)) {
          baseTypesToIndex.put(code, i);
        }
      }
      DescriptorProto baseChoiceType = makeChoiceType(choiceTypeBase.get(), elementList, field);
      final Set<String> uniqueTypes = new HashSet<>();
      List<FieldDescriptorProto> matchingFields =
          element.getTypeList().stream()
              .filter(type -> uniqueTypes.add(type.getCode().getValue()))
              .map(type -> baseChoiceType.getField(baseTypesToIndex.get(type.getCode().getValue())))
              .collect(Collectors.toList());
      // TODO: If a choice type is a slice of another choice type (not a pure
      // constraint, but actual slice) we'll need to update the name and type name as well.
      return Optional.of(
          baseChoiceType.toBuilder().clearField().addAllField(matchingFields).build());
    }
    if (isChoiceType(element)) {
      return Optional.of(makeChoiceType(element, elementList, field));
    }
    return Optional.empty();
  }

  private DescriptorProto addProfiledCodeableConcept(
      ElementDefinition element,
      List<ElementDefinition> elementList,
      List<ElementDefinition> codingSlices) {
    String codeableConceptStructDefUrl =
        CodeableConcept.getDescriptor()
            .getOptions()
            .getExtension(Annotations.fhirStructureDefinitionUrl);
    StructureDefinition codeableConceptDefinition =
        structDefDataByUrl.get(codeableConceptStructDefUrl).structDef;
    QualifiedType qualifiedType = getQualifiedFieldType(element, elementList);
    String fieldType = qualifiedType.type;
    DescriptorProto.Builder codeableConceptBuilder =
        generateMessage(
                codeableConceptDefinition.getSnapshot().getElementList().get(0),
                codeableConceptDefinition.getSnapshot().getElementList(),
                DescriptorProto.newBuilder())
            .toBuilder();
    codeableConceptBuilder.setName(fieldType.substring(fieldType.lastIndexOf(".") + 1));
    codeableConceptBuilder
        .getOptionsBuilder()
        .clearExtension(Annotations.structureDefinitionKind)
        .clearExtension(Annotations.fhirStructureDefinitionUrl)
        .addExtension(Annotations.fhirProfileBase, codeableConceptStructDefUrl);
    for (ElementDefinition codingSlice : codingSlices) {
      String fixedSystem = null;
      ElementDefinition codeDefinition = null;
      for (ElementDefinition codingField : getDirectChildren(codingSlice, elementList)) {
        String basePath = codingField.getBase().getPath().getValue();
        if (basePath.equals("Coding.system")) {
          fixedSystem = codingField.getFixed().getUri().getValue();
        }
        if (basePath.equals("Coding.code")) {
          codeDefinition = codingField;
        }
      }
      if (fixedSystem == null || codeDefinition == null) {
        System.out.println(
            "Warning: Coding slicing not handled because it does not have both a fixed system and a"
                + " code slice:\n"
                + codingSlice.getId());
      }

      if (codeDefinition.getFixed().hasCode()) {
        FieldDescriptorProto.Builder codingField =
            codeableConceptBuilder
                .addFieldBuilder()
                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                .setTypeName("." + fhirVersion.coreProtoPackage + ".CodingWithFixedCode")
                .setName(toFieldNameCase(codingSlice.getSliceName().getValue()))
                .setLabel(getFieldSize(codingSlice))
                .setNumber(codeableConceptBuilder.getFieldCount());
        codingField
            .getOptionsBuilder()
            .setExtension(Annotations.fhirInlinedCodingSystem, fixedSystem)
            .setExtension(
                Annotations.fhirInlinedCodingCode, codeDefinition.getFixed().getCode().getValue());
      } else {
        // For codings with fixed systems, we should inline a custom Coding type that incorporates
        // this information, e.g., inlining a strongly-typed Code enum.
        // For legacy reasons, we're only doing this for R4.
        // TODO: Do this for all versions before 1.0 release.
        if (packageInfo.getFhirVersion() == Annotations.FhirVersion.R4) {
          addCodingFieldWithFixedSystem(
              codeableConceptBuilder,
              qualifiedType.toQualifiedTypeString(),
              codingSlice,
              fixedSystem);
        } else {
          // Legacy "CodingWithFixedSystem"
          FieldDescriptorProto.Builder codingField =
              codeableConceptBuilder
                  .addFieldBuilder()
                  .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                  .setTypeName(CodingWithFixedSystem.getDescriptor().getFullName())
                  .setName(toFieldNameCase(codingSlice.getSliceName().getValue()))
                  .setLabel(getFieldSize(codingSlice))
                  .setNumber(codeableConceptBuilder.getFieldCount());
          codingField
              .getOptionsBuilder()
              .setExtension(Annotations.fhirInlinedCodingSystem, fixedSystem);
        }
      }
    }

    return codeableConceptBuilder.build();
  }

  private void addCodingFieldWithFixedSystem(
      DescriptorProto.Builder parentBuilder,
      String parentTypeString,
      ElementDefinition codingSlice,
      String fixedSystem) {
    String typeName = toFieldTypeCase(codingSlice.getSliceName().getValue());

    QualifiedType inlinedCodeType;
    if (valueSetTypesByUrl.containsKey(fixedSystem)) {
      Descriptor descriptor = valueSetTypesByUrl.get(fixedSystem);
      inlinedCodeType = new QualifiedType(descriptor.getName(), descriptor.getFile().getPackage());
    } else {
      // TODO: throw an error in strict mode
      inlinedCodeType = new QualifiedType("Code", fhirVersion.coreProtoPackage);
    }
    // Build and add a custom Coding message with a fixed system & inlined code
    parentBuilder.addNestedType(
        makeCodingMessageWithFixedSystem(typeName, fixedSystem, inlinedCodeType));

    // Add a field with that type.
    parentBuilder
        .addFieldBuilder()
        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
        .setTypeName(parentTypeString + "." + typeName)
        .setName(toFieldNameCase(codingSlice.getSliceName().getValue()))
        .setLabel(getFieldSize(codingSlice))
        .setNumber(parentBuilder.getFieldCount());
  }

  private DescriptorProto makeCodingMessageWithFixedSystem(
      String typeName, String fixedSystem, QualifiedType codeType) {
    DescriptorProto.Builder codingMessage = DescriptorProto.newBuilder();
    codingMessage.setName(typeName);
    codingMessage
        .getOptionsBuilder()
        .setExtension(Annotations.fhirFixedSystem, fixedSystem)
        .addExtension(
            Annotations.fhirProfileBase, baseStructDefsById.get("Coding").getUrl().getValue());

    List<FieldDescriptorProto> codingFields = new ArrayList<>();
    // Add in all coding fields that aren't code or system
    for (FieldDescriptorProto field : Coding.getDescriptor().toProto().getFieldList()) {
      if (!field.getName().equals("code") && !field.getName().equals("system")) {
        codingFields.add(field);
      }
    }

    codingFields.add(
        FieldDescriptorProto.newBuilder()
            .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
            .setTypeName(codeType.toQualifiedTypeString())
            .setName("code")
            .setNumber(5)
            .build());
    codingMessage.addAllField(
        codingFields.stream()
            .sorted((a, b) -> a.getNumber() - b.getNumber())
            .collect(Collectors.toList()));

    return codingMessage.build();
  }

  private static List<ElementDefinition> getDirectChildren(
      ElementDefinition element, List<ElementDefinition> elementList) {
    List<String> messagePathParts = Splitter.on('.').splitToList(element.getId().getValue());
    return getDescendants(element, elementList).stream()
        .filter(
            candidateElement -> {
              List<String> parts =
                  Splitter.on('.').splitToList(candidateElement.getId().getValue());
              // To be a direct child, the id should start with the parent id, and add a single
              // additional token.
              return parts.size() == messagePathParts.size() + 1;
            })
        .collect(Collectors.toList());
  }

  private static List<ElementDefinition> getDescendants(
      ElementDefinition element, List<ElementDefinition> elementList) {
    // The id of descendants should start with the parent id + at least one more token.
    String parentIdPrefix = element.getId().getValue() + ".";
    return elementList.stream()
        .filter(candidateElement -> candidateElement.getId().getValue().startsWith(parentIdPrefix))
        .collect(Collectors.toList());
  }

  private static final ElementDefinition.TypeRef STRING_TYPE =
      ElementDefinition.TypeRef.newBuilder().setCode(Uri.newBuilder().setValue("string")).build();

  private Optional<String> getPrimitiveRegex(ElementDefinition element) {
    switch (packageInfo.getFhirVersion()) {
      case DSTU2:
        // DSTU2 and STU3 have the same regex extension url, so just use the STU3 one.
      case STU3:
        List<com.google.fhir.stu3.proto.ElementDefinitionRegex> stu3Regex =
            ExtensionWrapper.fromExtensionsIn(element.getType(0))
                .getMatchingExtensions(
                    com.google.fhir.stu3.proto.ElementDefinitionRegex.getDefaultInstance());
        return stu3Regex.size() == 1
            ? Optional.of(stu3Regex.get(0).getValueString().getValue())
            : Optional.empty();
      case R4:
        List<com.google.fhir.r4.proto.Regex> r4Regex =
            ExtensionWrapper.fromExtensionsIn(element.getType(0))
                .getMatchingExtensions(com.google.fhir.r4.proto.Regex.getDefaultInstance());
        return r4Regex.size() == 1
            ? Optional.of(r4Regex.get(0).getValueString().getValue())
            : Optional.empty();
      default:
        throw new IllegalArgumentException(
            "Unrecognized fhir version: " + packageInfo.getFhirVersion());
    }
  }
  /** Generate the primitive value part of a datatype. */
  private void generatePrimitiveValue(StructureDefinition def, DescriptorProto.Builder builder) {
    String defId = def.getId().getValue();
    String valueFieldId = defId + ".value";
    // Find the value field.
    ElementDefinition valueElement =
        getElementDefinitionById(valueFieldId, def.getSnapshot().getElementList());
    // If a regex for this primitive type is present, add it as a message-level annotation.
    if (valueElement.getTypeCount() == 1) {
      Optional<String> regexOptional = getPrimitiveRegex(valueElement);
      if (regexOptional.isPresent()) {
        builder.setOptions(
            builder.getOptions().toBuilder()
                .setExtension(Annotations.valueRegex, regexOptional.get())
                .build());
      }
    }

    // The value field sometimes has no type. We need to add a fake one here for buildField
    // to succeed.  This will get overridden with the correct time,
    ElementDefinition elementWithType =
        valueElement.toBuilder().clearType().addType(STRING_TYPE).build();
    Optional<FieldDescriptorProto> fieldOptional =
        buildField(
            elementWithType,
            new ArrayList<ElementDefinition>() /* no child ElementDefinition */,
            1 /* nextTag */);
    if (fieldOptional.isPresent()) {
      FieldDescriptorProto field = fieldOptional.get();
      if (TIME_LIKE_PRECISION_MAP.containsKey(defId)) {
        // Handle time-like types differently.
        EnumDescriptorProto.Builder enumBuilder = PRECISION_ENUM.toBuilder();
        for (String value : TIME_LIKE_PRECISION_MAP.get(defId)) {
          enumBuilder.addValue(
              EnumValueDescriptorProto.newBuilder()
                  .setName(value)
                  .setNumber(enumBuilder.getValueCount()));
        }
        builder.addEnumType(enumBuilder);
        builder.addField(
            field
                .toBuilder()
                .clearTypeName()
                .setType(FieldDescriptorProto.Type.TYPE_INT64)
                .setName("value_us"));
        if (TYPES_WITH_TIMEZONE.contains(defId)) {
          builder.addField(TIMEZONE_FIELD);
        }
        builder.addField(
            FieldDescriptorProto.newBuilder()
                .setName("precision")
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setType(FieldDescriptorProto.Type.TYPE_ENUM)
                .setTypeName(
                    "."
                        + packageInfo.getProtoPackage()
                        + "."
                        + toFieldTypeCase(defId)
                        + ".Precision")
                .setNumber(builder.getFieldCount() + 1));
      } else {
        // Handle non-time-like types.
        // If they don't explicitly appear in the PRIMITIVE_TYPE_OVERRIDES, they are assumed
        // to be of type TYPE_STRING.
        FieldDescriptorProto.Type primitiveType =
            PRIMITIVE_TYPE_OVERRIDES.getOrDefault(defId, FieldDescriptorProto.Type.TYPE_STRING);
        builder.addField(field.toBuilder().clearTypeName().setType(primitiveType));
      }
    }
  }

  /**
   * Fields of the abstract types Element or BackboneElement are containers, which contain internal
   * fields (including possibly nested containers). Also, the top-level message is a container. See
   * https://www.hl7.org/fhir/backboneelement.html for additional information.
   */
  private static boolean isContainer(ElementDefinition element) {
    if (element.getTypeCount() != 1) {
      return false;
    }
    if (element.getId().getValue().indexOf('.') == -1) {
      return true;
    }
    String type = element.getType(0).getCode().getValue();
    return type.equals("BackboneElement") || type.equals("Element");
  }

  /**
   * Does this element have a single, well-defined type? For example: string, Patient or
   * Observation.ReferenceRange, as opposed to one of a set of types, most commonly encoded in field
   * with names like "value[x]".
   */
  private static boolean isSingleType(ElementDefinition element) {
    // If the type of this element is defined by referencing another element,
    // then it has a single defined type.
    if (element.getTypeCount() == 0 && element.hasContentReference()) {
      return true;
    }

    // Loop through the list of types. There may be multiple copies of the same
    // high-level type, for example, multiple kinds of References.
    Set<String> types = new HashSet<>();
    for (ElementDefinition.TypeRef type : element.getTypeList()) {
      if (type.hasCode()) {
        types.add(type.getCode().getValue());
      }
    }
    return types.size() == 1;
  }

  /** Returns true if this ElementDefinition is an extension with a profile. */
  private static boolean isExternalExtension(ElementDefinition element) {
    return element.getTypeCount() == 1
        && element.getType(0).getCode().getValue().equals("Extension")
        && element.getType(0).getProfileCount() == 1;
  }

  private static boolean isExtensionProfile(StructureDefinition def) {
    return def.getType().getValue().equals("Extension")
        && def.getDerivation().getValue() == TypeDerivationRuleCode.Value.CONSTRAINT;
  }

  private static boolean isChoiceType(ElementDefinition element) {
    return lastIdToken(element.getId().getValue()).isChoiceType;
  }

  private final Map<String, String> containerTypeCache = new HashMap<>();

  private String addToContainerTypeCache(String id, String containerType) {
    containerTypeCache.put(id, containerType);
    return containerType;
  }

  /** Extract the type of a container field, possibly by reference. */
  private String getContainerType(ElementDefinition element, List<ElementDefinition> elementList) {
    String id = element.getId().getValue();

    if (element.hasContentReference()) {
      // Find the named element which was referenced. We'll use the type of that element.
      // Strip the first character from the content reference since it is a '#'
      String referencedElementId = element.getContentReference().getValue().substring(1);
      ElementDefinition referencedElement =
          getElementDefinitionById(referencedElementId, elementList);
      if (!isContainer(referencedElement)) {
        throw new IllegalArgumentException(
            "ContentReference does not reference a container: " + element.getContentReference());
      }
      if (lastIdToken(referencedElementId).slicename != null
          && !isElementSupportedForSlicing(referencedElement)) {
        // This is a reference to a slice of a field, but the slice isn't a supported slice type.
        // Just use a reference to the base field.

        // TODO:  This logic assumes only a single level of slicing is present - the
        // base element could theoretically also be unsupported for slicing.
        referencedElement =
            getElementDefinitionById(
                referencedElementId.substring(0, referencedElementId.lastIndexOf(":")),
                elementList);
      }
      return getContainerType(referencedElement, elementList);
    }

    // Check the container type cache for this element id.
    // Note that we if a container type comes from a content reference in the above block,
    // we don't cache this as a profile can "override" an element with a given id with a different
    // content reference.
    if (containerTypeCache.containsKey(id)) {
      return containerTypeCache.get(id);
    }

    // The container type is the full type of the message that will be generated (minus package).
    // It is derived from the id (e.g., Medication.package.content), and these are usually equal
    // other than casing (e.g., Medication.Package.Content).
    // However, any parent in the path could have been renamed via a explicit type name extensions.

    // Check for explicit renamings on this element.
    List<ElementDefinitionExplicitTypeName> explicitTypeNames =
        ExtensionWrapper.fromExtensionsIn(element)
            .getMatchingExtensions(ElementDefinitionExplicitTypeName.getDefaultInstance());

    // Use explicit type name if present.  Otherwise, use the field_name, converted to FieldType
    // casing, as the submessage name.
    String typeName =
        toFieldTypeCase(
            explicitTypeNames.isEmpty()
                ? getJsonNameForElement(element)
                : explicitTypeNames.get(0).getValueString().getValue());

    int lastDotIndex = id.lastIndexOf('.');
    String packageString = "";
    if (lastDotIndex != -1) {
      packageString =
          getContainerType(
                  getElementDefinitionById(id.substring(0, lastDotIndex), elementList), elementList)
              + ".";
    }
    if (useLegacyTypeNaming()) {
      // TODO: For historical reasons we have a different naming convention for choice
      // types in stu3.  Remove this exception before releasing V1.0
      typeName = legacyRenaming(typeName, packageString);
    } else {
      if (isChoiceType(element)) {
        typeName = typeName + "X";
      }
      if (packageString.startsWith(typeName + ".")
          || packageString.contains("." + typeName + ".")) {
        typeName = typeName + "Type";
      }
    }
    return addToContainerTypeCache(id, packageString + typeName);
  }

  private String legacyRenaming(String typeName, String packageString) {
    return packageString.contains(".")
            && (packageString.startsWith(typeName + ".")
                || packageString.contains("." + typeName + ".")
                || typeName.equals("Timing")
                || typeName.equals("Age"))
        ? typeName + "Type"
        : typeName;
  }

  /**
   * Gets the field type and package of a potentially complex element. This handles choice types,
   * types that reference other elements, references, profiles, etc.
   */
  private QualifiedType getQualifiedFieldType(
      ElementDefinition element, List<ElementDefinition> elementList) {
    if (isContainer(element) || isChoiceType(element)) {
      return new QualifiedType(
          getContainerType(element, elementList), packageInfo.getProtoPackage());
    } else if (element.hasContentReference()) {
      // Get the type for this container from a named reference to another element.
      return new QualifiedType(
          getContainerType(element, elementList),
          isLocalContentReference(element)
              ? packageInfo.getProtoPackage()
              : fhirVersion.coreProtoPackage);
    } else if (isExtensionBackboneElement(element)) {
      return getInternalExtensionType(element, elementList);
    } else if (element.getType(0).getCode().getValue().equals("Reference")) {
      return new QualifiedType(
          USE_TYPED_REFERENCES ? getTypedReferenceName(element.getTypeList()) : "Reference",
          fhirVersion.coreProtoPackage);
    } else {
      if (element.getTypeCount() > 1) {
        throw new IllegalArgumentException(
            "Unknown multiple type definition on element: " + element.getId());
      }
      // Note: this is the "fhir type", e.g., Resource, BackboneElement, boolean,
      // not the field type name.
      String normalizedFhirTypeName =
          normalizeType(Iterables.getOnlyElement(element.getTypeList()));

      if (descendantsHaveSlices(element, elementList)) {
        // This is not a backbone element, but it has children that have slices.  This means we
        // cannot use the "stock" FHIR datatype here.
        // A common example of this is CodeableConcepts.  These are not themselves sliced, but the
        // repeated coding field on CodeableConcept can be.
        // This means we have to generate a nested CodeableConcept message that has these additional
        // fields.
        String containerType = getContainerType(element, elementList);
        int lastDotIndex = containerType.lastIndexOf(".");
        return new QualifiedType(
            containerType.substring(0, lastDotIndex + 1)
                + normalizedFhirTypeName
                + "For"
                + containerType.substring(lastDotIndex + 1),
            packageInfo.getProtoPackage());
      }

      if (normalizedFhirTypeName.equals("Resource")) {
        // We represent "Resource" FHIR types as "ContainedResource"
        if (packageInfo.getLocalContainedResource()) {
          return new QualifiedType("ContainedResource", packageInfo.getProtoPackage());
        }
        if (!packageInfo.getContainedResourcePackage().isEmpty()) {
          return new QualifiedType("ContainedResource", packageInfo.getContainedResourcePackage());
        }
        return new QualifiedType("ContainedResource", fhirVersion.coreProtoPackage);
      }

      Optional<QualifiedType> valueSetType = checkForTypeWithBoundValueSet(element, elementList);
      return valueSetType.orElse(
          new QualifiedType(normalizedFhirTypeName, fhirVersion.coreProtoPackage));
    }
  }

  private Optional<Descriptor> getBindingValueSetType(
      ElementDefinition element, List<ElementDefinition> elementList) {
    if (isSimpleInternalExtension(element, elementList)) {
      return getBindingValueSetType(getExtensionValueElement(element, elementList), elementList);
    }
    if (!useLegacyTypeNaming()
        && element.getBinding().getStrength().getValue() != BindingStrengthCode.Value.REQUIRED) {
      return Optional.empty();
    }
    String url = CanonicalWrapper.getUri(element.getBinding().getValueSet());
    if (url.isEmpty()) {
      return Optional.empty();
    }
    if (valueSetTypesByUrl.containsKey(url)) {
      return Optional.of(valueSetTypesByUrl.get(url));
    }
    // TODO: Throw an error in strict mode.
    return Optional.empty();
  }

  /** Build a single field for the proto. */
  private Optional<FieldDescriptorProto> buildField(
      ElementDefinition element, List<ElementDefinition> elementList, int nextTag) {
    FieldDescriptorProto.Label fieldSize = getFieldSize(element);
    if (fieldSize == null) {
      // This field has a max size of zero.  Do not emit a field.
      return Optional.empty();
    }

    FieldOptions.Builder options = FieldOptions.newBuilder();

    // Add a short description of the field.
    if (element.hasShort()) {
      options.setExtension(
          ProtoGeneratorAnnotations.fieldDescription, element.getShort().getValue());
    }

    addFhirPathConstraints(element, options);

    // Is this field required?
    if (element.getMin().getValue() == 1) {
      options.setExtension(
          Annotations.validationRequirement, Annotations.Requirement.REQUIRED_BY_FHIR);
    } else if (element.getMin().getValue() != 0) {
      System.out.println("Unexpected minimum field count: " + element.getMin().getValue());
    }
    String jsonFieldNameString = getJsonNameForElement(element);

    addFhirPathConstraints(element, options);

    if (isExternalExtension(element)) {
      // This is an extension with a single type defined by an external profile.
      // If we know about it, we'll inline a field for it.
      String profileUrl = element.getType(0).getProfile(0).getValue();
      StructureDefinitionData profileData = structDefDataByUrl.get(profileUrl);
      if (profileData == null) {
        // Unrecognized url.
        // TODO: add a lenient mode that just ignores this extension.
        throw new IllegalArgumentException("Encountered unknown extension url: " + profileUrl);
      }
      jsonFieldNameString = resolveSliceNameConflicts(jsonFieldNameString, element, elementList);
      options.setExtension(Annotations.fhirInlinedExtensionUrl, profileUrl);

      return Optional.of(
          buildFieldInternal(
                  jsonFieldNameString,
                  profileData.inlineType,
                  profileData.protoPackage,
                  nextTag,
                  fieldSize,
                  options.build())
              .build());
    }

    Optional<ElementDefinition> choiceTypeBase = getChoiceTypeBase(element);
    if (choiceTypeBase.isPresent()) {
      ElementDefinition choiceTypeBaseElement = choiceTypeBase.get();
      String baseJsonName = getJsonNameForElement(choiceTypeBaseElement);
      String baseContainerType = getContainerType(choiceTypeBaseElement, elementList);
      String containerType = getContainerType(element, elementList);
      containerType =
          containerType.substring(0, containerType.lastIndexOf(".") + 1)
              + baseContainerType.substring(baseContainerType.lastIndexOf(".") + 1);

      return Optional.of(
          buildFieldInternal(
                  baseJsonName,
                  containerType,
                  packageInfo.getProtoPackage(),
                  nextTag,
                  fieldSize,
                  options.build())
              .build());
    }

    if (isExtensionBackboneElement(element)) {
      // This is a internally-defined extension slice that will be inlined as a nested type for
      // complex extensions, or as a primitive type simple extensions.
      // Since extensions are sliced on url, the url for the extension matches the slicename.
      // Since the field name is the slice name wherever possible, annotating the field with the
      // inlined extension url is usually redundant.
      // We will only add it if we have to rename the field name.  This can happen if the slice
      // name is reserved (e.g., string or optional), or if the slice name conflicts with a field
      // on the base element (e.g., id or url), or if the slicename/url are in an unexpected casing.
      // If, for any reason, the urlField is not the camelCase version of the lower_underscore
      // field_name, add an annotation with the explicit name.
      jsonFieldNameString = resolveSliceNameConflicts(jsonFieldNameString, element, elementList);
      String url =
          getElementDefinitionById(element.getId().getValue() + ".url", elementList)
              .getFixed()
              .getUri()
              .getValue();
      if (!jsonFieldNameString.equals(url)) {
        options.setExtension(Annotations.fhirInlinedExtensionUrl, url);
      }
    }


    QualifiedType fieldType = getQualifiedFieldType(element, elementList);

    boolean isChoiceType = isChoiceType(element) || isChoiceTypeExtension(element, elementList);
    // Add typed reference options
    if (!isChoiceType
        && element.getTypeCount() > 0
        && element.getType(0).getCode().getValue().equals("Reference")) {
      for (ElementDefinition.TypeRef type : element.getTypeList()) {
        if (type.getCode().getValue().equals("Reference") && type.getTargetProfileCount() > 0) {
          for (Canonical referenceType : type.getTargetProfileList()) {
            if (!referenceType.getValue().isEmpty()) {
              addReferenceType(options, referenceType.getValue());
            }
          }
        }
      }
    }

    return Optional.of(
        buildFieldInternal(
                jsonFieldNameString,
                fieldType.type,
                fieldType.packageName,
                nextTag,
                fieldSize,
                options.build())
            .build());
  }

  // Adds any FHIRPath constraints from the definition.
  private static void addFhirPathConstraints(
      ElementDefinition element, FieldOptions.Builder options) {
    List<String> expressions =
        element.getConstraintList().stream()
            .filter(constraint -> constraint.hasExpression())
            .map(constraint -> constraint.getExpression().getValue())
            .filter(expression -> !EXCLUDED_FHIR_CONSTRAINTS.contains(expression))
            .collect(Collectors.toList());

    if (!expressions.isEmpty()) {
      options.setExtension(Annotations.fhirPathConstraint, expressions);
    }
  }

  private static FieldDescriptorProto.Label getFieldSize(ElementDefinition element) {
    if (element.getMax().getValue().equals("0")) {
      // This field doesn't actually exist.
      return null;
    }
    return element.getMax().getValue().equals("1")
        ? FieldDescriptorProto.Label.LABEL_OPTIONAL
        : FieldDescriptorProto.Label.LABEL_REPEATED;
  }

  private boolean isLocalContentReference(ElementDefinition element) {
    // TODO: more sophisticated logic.  This wouldn't handle references to fields in
    // other elements in a non-core package
    if (!element.hasContentReference()) {
      return false;
    }
    String rootType = Splitter.on(".").limit(2).splitToList(element.getId().getValue()).get(0);
    return element.getContentReference().getValue().startsWith("#" + rootType);
  }

  /**
   * Returns the field name that should be used for an element, in jsonCase. If element is a slice,
   * uses that slice name. Since the id token slice name is all-lowercase, uses the SliceName field.
   * Otherwise, uses the last token's pathpart. Logs a warning if the slice name in the id token
   * does not match the SliceName field.
   */
  // TODO: Handle reslices. Could be as easy as adding it to the end of SliceName.
  private String getJsonNameForElement(ElementDefinition element) {
    IdToken lastToken = lastIdToken(element.getId().getValue());
    if (lastToken.slicename == null || element.getId().getValue().indexOf(".") == -1) {
      // There is either no slicename, or the "slice" is on the root element, which is a meaningless
      // thing that UsCore sometimes does.
      return toJsonCase(lastToken.pathpart);
    }
    String sliceName = element.getSliceName().getValue();
    if (!lastToken.slicename.equals(sliceName.toLowerCase())) {
      // TODO: pull this into a common validator that runs ealier.
      logDiscrepancies(
          "Warning: Inconsistent slice name for element with id "
              + element.getId().getValue()
              + " and slicename "
              + element.getSliceName());
    }
    return toJsonCase(sliceName);
  }

  private static boolean descendantsHaveSlices(
      ElementDefinition element, List<ElementDefinition> elementList) {
    return getDescendants(element, elementList).stream()
        .anyMatch(candidate -> candidate.hasSliceName());
  }

  // Given a potential slice field name and an element, returns true if that slice name would
  // conflict with the field name of any siblings to that elements.
  // TODO: This only checks against non-slice names.  Theoretically, you could have
  // two identically-named slices of different base fields.
  private static String resolveSliceNameConflicts(
      String jsonFieldName, ElementDefinition element, List<ElementDefinition> elementList) {
    if (RESERVED_FIELD_NAMES.contains(jsonFieldName)) {
      return jsonFieldName + "Slice";
    }
    String elementId = element.getId().getValue();
    int lastDotIndex = elementId.lastIndexOf('.');
    if (lastDotIndex == -1) {
      // This is a profile on a top-level Element. There can't be any conflicts.
      return jsonFieldName;
    }
    String parentElementId = elementId.substring(0, lastDotIndex);
    List<ElementDefinition> elementsWithIdsConflictingWithSliceName =
        getDirectChildren(getElementDefinitionById(parentElementId, elementList), elementList)
            .stream()
            .filter(
                candidateElement ->
                    toFieldNameCase(lastIdToken(candidateElement.getId().getValue()).pathpart)
                            .equals(jsonFieldName)
                        && !candidateElement.getBase().getPath().getValue().equals("Extension.url"))
            .collect(Collectors.toList());

    return elementsWithIdsConflictingWithSliceName.isEmpty()
        ? jsonFieldName
        : jsonFieldName + "Slice";
  }

  // TODO: memoize
  private Optional<ElementDefinition> getChoiceTypeBase(ElementDefinition element) {
    if (!element.hasBase()) {
      return Optional.empty();
    }
    String basePath = element.getBase().getPath().getValue();
    if (basePath.equals("Extension.value[x]")) {
      // Extension value fields extend from choice-types, but since single-typed extensions will be
      // inlined as that type anyway, there's no point in generating a choice type for them.
      return Optional.empty();
    }
    String baseType = Splitter.on(".").splitToList(basePath).get(0);
    if (basePath.endsWith("[x]")) {
      ElementDefinition choiceTypeBase =
          getElementDefinitionById(
              basePath, baseStructDefsById.get(baseType).getSnapshot().getElementList());
      return Optional.of(choiceTypeBase);
    }
    if (!baseType.equals("Element")) {
      // Traverse up the tree to check for a choice type in this element's ancestry.
      if (!baseStructDefsById.containsKey(baseType)) {
        throw new IllegalArgumentException("Unknown StructureDefinition id: " + baseType);
      }
      ElementDefinition baseElement =
          getElementDefinitionById(
              basePath, baseStructDefsById.get(baseType).getSnapshot().getElementList());
      if (baseElement.getId().equals(element.getId())) {
        // Starting with r4, elements that don't inherit from anything list themselves as base :/
        return Optional.empty();
      }
      return getChoiceTypeBase(baseElement);
    }
    return Optional.empty();
  }

  private boolean useLegacyTypeNaming() {
    return useLegacyTypeNaming(packageInfo.getFhirVersion());
  }

  private static boolean useLegacyTypeNaming(Annotations.FhirVersion version) {
    return version != Annotations.FhirVersion.R4;
  }

  /** Add a choice type container message to the proto. */
  private DescriptorProto makeChoiceType(
      ElementDefinition element, List<ElementDefinition> elementList, FieldDescriptorProto field) {
    List<String> typeNameParts =
        Splitter.on('.').splitToList(getContainerType(element, elementList));
    DescriptorProto.Builder choiceType =
        DescriptorProto.newBuilder().setName(typeNameParts.get(typeNameParts.size() - 1));
    choiceType.getOptionsBuilder().setExtension(Annotations.isChoiceType, true);
    // TODO: Remove legacy renaming rules prior to V1.0
    OneofDescriptorProto.Builder oneof =
        choiceType
            .addOneofDeclBuilder()
            .setName(useLegacyTypeNaming() ? field.getName() : "choice");

    int nextTag = 1;
    // Group types.
    List<ElementDefinition.TypeRef> types = new ArrayList<>();
    List<String> referenceTypes = new ArrayList<>();
    Set<String> foundTypes = new HashSet<>();
    for (ElementDefinition.TypeRef type : element.getTypeList()) {
      if (!foundTypes.contains(type.getCode().getValue())) {
        types.add(type);
        foundTypes.add(type.getCode().getValue());
      }

      if (type.getCode().getValue().equals("Reference") && type.getTargetProfileCount() > 0) {
        for (Canonical referenceType : type.getTargetProfileList()) {
          if (!referenceType.getValue().isEmpty()) {
            referenceTypes.add(referenceType.getValue());
          }
        }
      }
    }

    for (ElementDefinition.TypeRef t : types) {
      String fieldType = normalizeType(t);
      String fieldName = t.getCode().getValue();
      // TODO:  This assumes all types in a oneof are core FHIR types.  In order to
      // support custom types, we'll need to load the structure definition for the type and check
      // against knownStructureDefinitionPackages
      FieldOptions.Builder options = FieldOptions.newBuilder();
      if (fieldName.equals("Reference")) {
        for (String referenceType : referenceTypes) {
          addReferenceType(options, referenceType);
        }
      }
      FieldDescriptorProto.Builder fieldBuilder =
          buildFieldInternal(
                  fieldName,
                  fieldType,
                  fhirVersion.coreProtoPackage,
                  nextTag++,
                  FieldDescriptorProto.Label.LABEL_OPTIONAL,
                  options.build())
              .setOneofIndex(0);
      // TODO: change the oneof name to avoid this.
      if (fieldBuilder.getName().equals(oneof.getName())) {
        fieldBuilder.setJsonName(fieldBuilder.getName());
        fieldBuilder.setName(fieldBuilder.getName() + "_value");
      }
      choiceType.addField(fieldBuilder);
    }
    return choiceType.build();
  }

  private void addReferenceType(FieldOptions.Builder options, String referenceUrl) {
    options.addExtension(
        Annotations.validReferenceType, getBaseStructureDefinitionData(referenceUrl).inlineType);
  }

  /**
   * Given a structure definition url, returns the base (FHIR) structure definition data for that
   * type.
   */
  private StructureDefinitionData getBaseStructureDefinitionData(String url) {
    StructureDefinitionData defData = structDefDataByUrl.get(url);
    while (isProfile(defData.structDef)) {
      defData = structDefDataByUrl.get(defData.structDef.getBaseDefinition().getValue());
    }
    return defData;
  }

  private FieldDescriptorProto.Builder buildFieldInternal(
      String fieldJsonName,
      String fieldType,
      String fieldPackage,
      int tag,
      FieldDescriptorProto.Label size,
      FieldOptions options) {
    FieldDescriptorProto.Builder builder =
        FieldDescriptorProto.newBuilder()
            .setNumber(tag)
            .setType(FieldDescriptorProto.Type.TYPE_MESSAGE);
    builder.setLabel(size);
    builder.setTypeName("." + fieldPackage + "." + fieldType);

    if (RESERVED_FIELD_NAMES.contains(fieldJsonName)) {
      builder.setName(toFieldNameCase(fieldJsonName + "Value")).setJsonName(fieldJsonName);
    } else {
      builder.setName(toFieldNameCase(fieldJsonName));
    }

    // Add annotations.
    if (!options.equals(FieldOptions.getDefaultInstance())) {
      builder.setOptions(options);
    }
    return builder;
  }

  private String normalizeType(ElementDefinition.TypeRef type) {
    if (type.getProfileCount() != 1 || type.getProfile(0).getValue().isEmpty()) {
      return toFieldTypeCase(type.getCode().getValue());
    }
    String profileUrl = type.getProfile(0).getValue();
    if (structDefDataByUrl.containsKey(profileUrl)) {
      return structDefDataByUrl.get(profileUrl).inlineType;
    }
    throw new IllegalArgumentException(
        "Unable to deduce typename for profile: " + profileUrl + " on " + type);
  }

  private Optional<QualifiedType> checkForTypeWithBoundValueSet(
      ElementDefinition element, List<ElementDefinition> elementList) {
    // Note that for Simple extensions that are inlined as a single type, we need to actually check
    // the internal value element on the extension, even though the element we're replacing and
    // naming the field after is the extension itself.  Thus, here we differentiate between
    // "value element" and "naming element".
    // E.g., for element mySubExtension, which has a valueCoding element on it, the datatype will be
    // generated from the valueElement, valueCoding in this case, but the name should be based on
    // the original, mySubExtension element.
    //
    // For all cases other than simple sub extensions, the value element is equal to the naming
    // element.
    ElementDefinition namingElement = element;
    ElementDefinition valueElement =
        isSimpleInternalExtension(element, elementList)
            ? getExtensionValueElement(element, elementList)
            : element;
    if (getDistinctTypeCount(valueElement) == 1) {
      String typeName = valueElement.getType(0).getCode().getValue();
      Optional<Descriptor> valueSetType = getBindingValueSetType(valueElement, elementList);
      if (valueSetType.isPresent()) {
        if (typeName.equals("code")) {
          return Optional.of(
              new QualifiedType(
                  valueSetType.get().getName(), valueSetType.get().getFile().getPackage()));
        }
        if (!useLegacyTypeNaming() && typeName.equals("Coding")) {
          return Optional.of(
              new QualifiedType(
                  getContainerType(namingElement, elementList) + "Coding",
                  packageInfo.getProtoPackage()));
        }
      }
      // TODO: Handle bound systems on CodeableConcepts
      // TODO: return an error for unhandled types with required bindings in strict mode
    }
    return Optional.empty();
  }

  private Optional<DescriptorProto> makeStandaloneCodingMessageWithFixedSystemIfRequired(
      ElementDefinition element, List<ElementDefinition> elementList) {
    // Note that in the case of sub extensions, the element will be an extension, but the field
    // we're using to generate the type will be the "value" field on that extension.
    // In all other cases, the value element is the same as the element passed in.
    ElementDefinition valueElement = element;
    if (isSimpleInternalExtension(element, elementList)) {
      valueElement = getExtensionValueElement(element, elementList);
    }
    String type = valueElement.getType(0).getCode().getValue();
    if (type.equals("Coding") && !useLegacyTypeNaming()) {
      Optional<Descriptor> boundCodeOptional = getBindingValueSetType(valueElement, elementList);
      if (boundCodeOptional.isPresent()) {
        Descriptor boundCode = boundCodeOptional.get();
        String fieldType = checkForTypeWithBoundValueSet(element, elementList).get().type;
        // Protos are nested by adding the nested proto directly to the parent proto.
        // So, we don't want the parent proto tokens in the type name.
        fieldType = fieldType.substring(fieldType.lastIndexOf(".") + 1);
        return Optional.of(
            makeCodingMessageWithFixedSystem(
                fieldType,
                AnnotationUtils.getFhirValuesetUrl(boundCode),
                new QualifiedType(boundCode.getName(), boundCode.getFile().getPackage())));
      }
    }
    return Optional.empty();
  }

  private static final Pattern WORD_BREAK_PATTERN = Pattern.compile("[^A-Za-z0-9]+([A-Za-z0-9])");

  // Converts a FHIR id strings to UpperCamelCasing for FieldTypes using a regex pattern that
  // considers hyphen, underscore and space to be word breaks.
  private static String toFieldTypeCase(String type) {
    String normalizedType = type;
    if (Character.isLowerCase(type.charAt(0))) {
      normalizedType = type.substring(0, 1).toUpperCase() + type.substring(1);
    }
    Matcher matcher = WORD_BREAK_PATTERN.matcher(normalizedType);
    StringBuffer typeBuilder = new StringBuffer();
    boolean foundMatch = false;
    while (matcher.find()) {
      foundMatch = true;
      matcher.appendReplacement(typeBuilder, matcher.group(1).toUpperCase());
    }
    return foundMatch ? matcher.appendTail(typeBuilder).toString() : normalizedType;
  }

  private static String toFieldNameCase(String fieldName) {
    // Make sure the field name is snake case, as required by the proto style guide.
    String normalizedFieldName = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, fieldName);
    // TODO: add more normalization here if necessary.  I think this is enough for now.
    return normalizedFieldName;
  }

  private static String toJsonCase(String fieldName) {
    if (fieldName.contains("-")) {
      return CaseFormat.LOWER_HYPHEN.to(CaseFormat.LOWER_CAMEL, fieldName);
    }
    if (fieldName.contains("_")) {
      return CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, fieldName);
    }
    return CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_CAMEL, fieldName);
  }

  // Returns the only element in the list matching a given id.
  // Throws IllegalArgumentException if zero or more than one matching element is found.
  private static ElementDefinition getElementDefinitionById(
      String id, List<ElementDefinition> elements) {
    return getOptionalElementDefinitionById(id, elements)
        .orElseThrow(() -> new IllegalArgumentException("No element with id: " + id));
  }

  // Returns the only element in the list matching a given id, or an empty optional if none are
  // found.
  // Throws IllegalArgumentException if more than one matching element is found.
  private static Optional<ElementDefinition> getOptionalElementDefinitionById(
      String id, List<ElementDefinition> elements) {
    return elements.stream()
        .filter(element -> element.getId().getValue().equals(id))
        .collect(MoreCollectors.toOptional());
  }

  private String getTypedReferenceName(List<ElementDefinition.TypeRef> typeList) {
    // Use a Tree Set to have a stable sort.
    TreeSet<String> refTypes =
        typeList.stream()
            .flatMap(type -> type.getTargetProfileList().stream())
            .map(profile -> profile.getValue())
            .collect(Collectors.toCollection(TreeSet::new));

    String refType = null;
    for (String r : refTypes) {
      if (!r.isEmpty()) {
        if (structDefDataByUrl.containsKey(r)) {
          r = structDefDataByUrl.get(r).inlineType;
        } else {
          throw new IllegalArgumentException("Unsupported reference profile: " + r);
        }
        if (refType == null) {
          refType = r;
        } else {
          refType = refType + "Or" + r;
        }
      }
    }
    if (refType != null && !refType.equals("Resource")) {
      // Specialize the reference type.
      return refType + "Reference";
    }
    return "Reference";
  }

  private static boolean isExtensionBackboneElement(ElementDefinition element) {
    // An element is an extension element if either
    // A) it is a root element with id "Extension" and is a derivation from a base element or
    // B) it is a slice on an extension that is not defined by an external profile.
    return (element.getId().getValue().equals("Extension") && element.hasBase())
        || (element.getBase().getPath().getValue().endsWith(".extension")
            && lastIdToken(element.getId().getValue()).slicename != null
            && element.getType(0).getProfileCount() == 0);
  }

  // Returns the QualifiedType (type + package) of a simple extension.
  private QualifiedType getSimpleExtensionDefinitionType(StructureDefinition def) {
    if (!isExtensionProfile(def)) {
      throw new IllegalArgumentException(
          "StructureDefinition is not an extension profile: " + def.getId().getValue());
    }
    ElementDefinition element = def.getSnapshot().getElement(0);
    List<ElementDefinition> elementList = def.getSnapshot().getElementList();
    if (isSingleTypedExtensionDefinition(def)) {
      return getSimpleInternalExtensionType(element, elementList);
    }
    if (isChoiceTypeExtension(element, elementList)) {
      String choiceField = useLegacyTypeNaming() ? ".Value" : ".ValueX";
      return new QualifiedType(getTypeName(def) + choiceField, packageInfo.getProtoPackage());
    }
    throw new IllegalArgumentException(
        "StructureDefinition is not a simple extension: " + def.getId().getValue());
  }

  private static ElementDefinition getExtensionValueElement(
      ElementDefinition element, List<ElementDefinition> elementList) {
    for (ElementDefinition child : getDirectChildren(element, elementList)) {
      if (child.getBase().getPath().getValue().equals("Extension.value[x]")) {
        return child;
      }
    }
    throw new IllegalArgumentException(
        "Element " + element.getId().getValue() + " has no value element");
  }

  private QualifiedType getSimpleInternalExtensionType(
      ElementDefinition element, List<ElementDefinition> elementList) {
    ElementDefinition valueElement = getExtensionValueElement(element, elementList);

    if (valueElement.getMax().getValue().equals("0")) {
      // There is no value element, this is a complex extension
      return null;
    }
    if (getDistinctTypeCount(valueElement) == 1) {
      // This is a primitive extension with a single type
      String rawType = valueElement.getType(0).getCode().getValue();

      Optional<QualifiedType> valueSetType = checkForTypeWithBoundValueSet(element, elementList);
      return valueSetType.orElse(
          new QualifiedType(toFieldTypeCase(rawType), fhirVersion.coreProtoPackage));
    }
    // This is a choice-type extension that will be inlined as a message.
    return new QualifiedType(getContainerType(element, elementList), packageInfo.getProtoPackage());
  }

  private static long getDistinctTypeCount(ElementDefinition element) {
    // Don't do fancier logic if fast logic is sufficient.
    if (element.getTypeCount() < 2 || USE_TYPED_REFERENCES) {
      return element.getTypeCount();
    }
    return element.getTypeList().stream().map(type -> type.getCode()).distinct().count();
  }

  private static boolean isChoiceTypeExtension(
      ElementDefinition element, List<ElementDefinition> elementList) {
    if (!isExtensionBackboneElement(element)) {
      return false;
    }
    ElementDefinition valueElement = getExtensionValueElement(element, elementList);
    return !valueElement.getMax().getValue().equals("0") && getDistinctTypeCount(valueElement) > 1;
  }

  // TODO: Clean up some of the terminology - 'internal' is misleading here.
  private static boolean isSimpleInternalExtension(
      ElementDefinition element, List<ElementDefinition> elementList) {
    return isExtensionBackboneElement(element)
        && !getExtensionValueElement(element, elementList).getMax().getValue().equals("0");
  }

  private boolean isComplexInternalExtension(
      ElementDefinition element, List<ElementDefinition> elementList) {
    return isExtensionBackboneElement(element) && !isSimpleInternalExtension(element, elementList);
  }

  private boolean isSingleTypedExtensionDefinition(StructureDefinition def) {
    ElementDefinition element = def.getSnapshot().getElement(0);
    List<ElementDefinition> elementList = def.getSnapshot().getElementList();
    return isSimpleInternalExtension(element, elementList)
        && getDistinctTypeCount(getExtensionValueElement(element, elementList)) == 1;
  }

  /**
   * Returns the type that should be used for an internal extension. If this is a simple internal
   * extension, uses the appropriate primitive type. If this is a complex internal extension, treats
   * the element like a backbone container.
   */
  private QualifiedType getInternalExtensionType(
      ElementDefinition element, List<ElementDefinition> elementList) {
    return isSimpleInternalExtension(element, elementList)
        ? getSimpleInternalExtensionType(element, elementList)
        : new QualifiedType(getContainerType(element, elementList), packageInfo.getProtoPackage());
  }

  /**
   * Derives a message type for a Profile. Uses the name field from the StructureDefinition. If the
   * StructureDefinition has a context indicating a single Element type, that type is used as a
   * prefix. If the element is a simple extension, returns the type defined by the extension.
   */
  private static String getProfileTypeName(StructureDefinition def) {
    String name = toFieldTypeCase(def.getName().getValue());
    Set<String> contexts = new HashSet<>();
    Splitter splitter = Splitter.on('.').limit(2);
    for (StructureDefinition.Context context : def.getContextList()) {
      // Only interest in the top-level resource.
      if (context.getType().getValue() == ExtensionContextTypeCode.Value.ELEMENT) {
        contexts.add(splitter.splitToList(context.getExpression().getValue()).get(0));
      }
    }
    if (contexts.size() == 1) {
      String context = Iterables.getOnlyElement(contexts);
      if (!context.equals("*")) {
        name = context + name;
      }
    }
    return toFieldTypeCase(name);
  }

  // TODO: consider supporting more types of slicing.
  private static boolean isElementSupportedForSlicing(ElementDefinition element) {
    return element.getTypeCount() == 1
        && (element.getType(0).getCode().getValue().equals("Extension")
            || element.getType(0).getCode().getValue().equals("Coding"));
  }

  private static final boolean PRINT_SNAPSHOT_DISCREPANCIES = false;

  private static void logDiscrepancies(String msg) {
    if (PRINT_SNAPSHOT_DISCREPANCIES) {
      System.out.println(msg);
    }
  }

  // We generate protos based on the "Snapshot" view of the proto, but these are often
  // generated off of the "Differential" view, which can be buggy.  So, before processing the
  // snapshot, do a pass over the differential and correct any inconsistencies in the snapshot.
  private static StructureDefinition reconcileSnapshotAndDifferential(StructureDefinition def) {
    // Generate a map from (element id) -> (element) for all elements in the Differential view.
    Map<String, ElementDefinition> diffs =
        def.getDifferential()
            .getElementList()
            .stream()
            .collect(
                Collectors.toMap((element) -> element.getId().getValue(), Function.identity()));

    StructureDefinition.Builder defBuilder = def.toBuilder();
    defBuilder.getSnapshotBuilder().clearElement();
    for (ElementDefinition element : def.getSnapshot().getElementList()) {
      ElementDefinition elementDiffs = diffs.get(element.getId().getValue());
      defBuilder
          .getSnapshotBuilder()
          .addElement(
              elementDiffs == null
                  ? element
                  : (ElementDefinition)
                      reconcileMessage(
                          element,
                          elementDiffs,
                          def.getId().getValue(),
                          element.getId().getValue(),
                          ""));
    }
    return defBuilder.build();
  }

  private static Message reconcileMessage(
      Message snapshot,
      Message differential,
      String structureDefinitionId,
      String elementId,
      String fieldpath) {
    Message.Builder reconciledElement = snapshot.toBuilder();
    for (FieldDescriptor field : reconciledElement.getDescriptorForType().getFields()) {
      String subFieldpath = (fieldpath.isEmpty() ? "" : (fieldpath + ".")) + field.getJsonName();
      if (!field.isRepeated()
          && differential.hasField(field)
          && !differential.getField(field).equals(reconciledElement.getField(field))) {
        if (AnnotationUtils.isPrimitiveType(field.getMessageType())) {
          reconciledElement.setField(field, differential.getField(field));
          logDiscrepancies(
              "Warning: found inconsistent Snapshot for "
                  + structureDefinitionId
                  + ".  Field \""
                  + subFieldpath
                  + "\" on "
                  + elementId
                  + " has snapshot\n"
                  + snapshot.getField(field)
                  + "but differential\n"
                  + differential.getField(field)
                  + "Using differential value for protogeneration.\n");
        } else {
          reconciledElement.setField(
              field,
              reconcileMessage(
                  (Message) snapshot.getField(field),
                  (Message) differential.getField(field),
                  structureDefinitionId,
                  elementId,
                  subFieldpath));
        }
      } else if (field.isRepeated()) {
        // For repeated fields, make sure each element in the differential appears in the snapshot.
        Set<Message> valuesInSnapshot = new HashSet<>();
        for (int i = 0; i < snapshot.getRepeatedFieldCount(field); i++) {
          valuesInSnapshot.add((Message) snapshot.getRepeatedField(field, i));
        }
        for (int i = 0; i < differential.getRepeatedFieldCount(field); i++) {
          Message differentialValue = (Message) differential.getRepeatedField(field, i);
          if (!valuesInSnapshot.contains(differentialValue)) {
            logDiscrepancies(
                "Warning: found inconsistent Snapshot for "
                    + structureDefinitionId
                    + ".  Field \""
                    + subFieldpath
                    + "\" on "
                    + elementId
                    + " has value on differential that is missing from snapshot:\n"
                    + differentialValue
                    + "Adding in for use in protogeneration.\n");
            reconciledElement.addRepeatedField(field, differentialValue);
          }
        }
      }
    }
    return reconciledElement.build();
  }
}
