# How files are laid out in the underlying storage

The storage service is meant to be used for a digital archive, where files aren't being saved for weeks or months -- we're preserving them for *years*.
Our files will outlive any particular software stack (including the storage service), so we want to organise them in a way that will remain useful after all the code is gone.

Within each storage location (Amazon S3 bucket, Azure Blob container), we group bags into related "spaces" of content.
Within each space, bags are further grouped by "external identifier", and then each version of a bag is in a versioned prefix.

Although bags are uploaded as compressed archives, they are stored as unpacked files:

```
│
├── born-digital/
│     ├── A000001/
│     │    ├── v1/
│     │    │    ├── bagit.txt
│     │    │    ├── bag-info.txt
│     │    │    └── data/
│     │    │          ├── b31497652_0001.jp2
│     │    │          └── ...
│     │    └── v2/
│     │         └── ...
│     │
│     └── A000002/
│          └── v1/
│               └── ...
│
└── digitised/
      ├── b1000001/
      │     ├── v1/
      │     │    └── ...
      │     └── v2/
      │          └── ...
      └── ...

```

Here, the two spaces are "born-digital" and "digitised".
Within these spaces, the external identifiers are "A000001", "A000002" and "b1000001".

Each storage location uses an identical file layout.
They should be byte-for-byte identical.
This keeps the storage service simple, and makes it easier to compare two locations.

## Benefits

-   This layout is human-readable.

-   This layout is standalone and self-contained.

    You don't need the storage service or its databases to make sense of this storage -- all the key information is contained within the structure of the files and the BagIt manifests.

-   This gives us a flexible way to manage permissions.

    Cloud storage providers like Amazon S3 and Azure Blob can use prefixes to manage permissions, e.g. allow access to all files that start `/digitised` but not `born-digital`.
    Constructing a hierarchical key means we can be flexible in what permissions we assign, e.g.

    -   allow access to everything (`allow *`)
    -   allow access to a single space (`allow digitised/*`)
    -   allow access to a single identifier within a space (`allow digitised/b1000001/*`)

-   This allows us to keep test bags separate.
    If we're testing the storage service, we put bags in a `testing` space, which keeps them separate from real content.

    When we were decommissioning Wellcome's previous storage system, we spent a lot of time trying to work out what content was test content that could be safely discarded, and what needed to be migrated to the new service.

## Limitations

-   This layout is designed for use in cloud storage, and in particular in an object store.
    It would not work with a hierarchical filesystem.

    In an object store, we can put millions of keys in the prefix for a single space, and it'll be fine.
    In a hierarchical filesystem, putting millions of folders inside a single folder would cause performance issues.

-   Because we replicate an identical file layout to every storage provider, we have to use keys that are valid in every storage provider simultaneously.

    i.e. every key we use has to be a valid S3 key and a valid Azure Blob name.

## Compression

Although bags are uploaded as compressed archives, they are stored as unpacked files.
We took this approach for several reasons:

-   It mirrored the structure of our previous storage repository.

-   Ingesting compressed bags means we can pass them around atomically until they get ingested by the storage service.
    We don't have to worry about a bag changing midway through an ingest.

-   Most of our bags are digitised content -- a collection of medium-sized images (a few MB each) and a single metadata file.
    For both humans and automated systems, it's useful to be able to retrieve individual images, and this is easier if the bags are stored as unpacked files.

-   Because the storage service doesn't do any processing of the bag beyond removing the top level of compression, you can still store a compressed archive by wrapping it within another bag:

    ```
    ├── bagit.txt
    ├── bag-info.txt
    └── data/
          └── compressed_files.zip
    ```
